package io.majung.pleos.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.offsetPoint
import io.majung.pleos.model.GeoPoint
import io.majung.pleos.model.LiveMapState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.io.File
import org.osmdroid.util.GeoPoint as OsmPoint

/** ARGB color ints. 화면 디자인 쪽에서 채운다. */
data class MapPalette(
    val car: Int, val carRing: Int,
    val passenger: Int, val passengerRing: Int,
    val pickup: Int, val pickupInk: Int,
    val route: Int
)

data class MapTheme(val dark: Boolean, val palette: MapPalette)

private const val ATTRIBUTION = "© OpenStreetMap contributors"
private val DEFAULT_CENTER = OsmPoint(37.4988, 127.0614)

/** OSM 표준 타일(키 불필요). 밤에는 타일에 색 행렬(채도 낮춤 + 반전)을 씌워 어두운 지도로 만든다. */
private val OSM_TILES = XYTileSource(
    "OpenStreetMap", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    ATTRIBUTION
)

private fun tileSource(@Suppress("UNUSED_PARAMETER") dark: Boolean): XYTileSource = OSM_TILES

private val NIGHT_FILTER: android.graphics.ColorMatrixColorFilter by lazy {
    val m = android.graphics.ColorMatrix().apply { setSaturation(0.35f) }
    val invert = android.graphics.ColorMatrix(floatArrayOf(
        -0.82f, 0f, 0f, 0f, 225f,
        0f, -0.82f, 0f, 0f, 225f,
        0f, 0f, -0.82f, 0f, 228f,
        0f, 0f, 0f, 1f, 0f
    ))
    m.postConcat(invert)
    android.graphics.ColorMatrixColorFilter(m)
}

/** 차량·동승자·픽업 지점을 한 지도에 그린다. 모든 좌표는 state에서만 온다. */
@Composable
fun MajungMap(state: LiveMapState, theme: MapTheme, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        createMapView(context)
    }
    val overlay = remember { MajungOverlay(context.resources.displayMetrics.density) }
    val fit = remember { FitTracker() }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView.also { it.overlays.add(overlay) } },
        update = { map ->
            if (map.tileProvider.tileSource.name() != tileSource(theme.dark).name()) {
                map.setTileSource(tileSource(theme.dark))
            }
            map.overlayManager.tilesOverlay.setColorFilter(if (theme.dark) NIGHT_FILTER else null)
            overlay.state = state
            overlay.palette = theme.palette
            fit.fitIfNeeded(map, state)
            map.invalidate()
        }
    )
}

/** 지도 하단 등에 둘 수 있는 아주 작은 저작자 표기 (타일 사용 조건). */
@Composable
fun MajungMapAttribution(modifier: Modifier = Modifier) {
    Text("© OpenStreetMap, © CARTO", fontSize = 9.sp, modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp))
}

private fun createMapView(context: Context): MapView = MapView(context).apply {
    setTileSource(tileSource(dark = true))
    setTilesScaledToDpi(true)
    setMultiTouchControls(true)
    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
    isHorizontalMapRepetitionEnabled = false
    isVerticalMapRepetitionEnabled = false
    minZoomLevel = 13.0
    maxZoomLevel = 19.0
    controller.setZoom(16.0)
    controller.setCenter(DEFAULT_CENTER)
}

/** 점들이 5 m 넘게 움직였거나 첫 위치가 들어왔을 때만 화면을 다시 맞춘다 (사용자 손동작을 매 프레임 덮어쓰지 않도록). */
private class FitTracker {
    private var lastPoints: List<GeoPoint> = emptyList()
    private val engine = CurbsideCarFinderEngine()

    fun fitIfNeeded(map: MapView, state: LiveMapState) {
        val points = listOfNotNull(state.car, state.passenger, state.pickup)
        if (points.isEmpty()) return
        val changed = points.size != lastPoints.size ||
            points.zip(lastPoints).any { (a, b) -> engine.distanceMeters(a.lat, a.lng, b.lat, b.lng) > 5 }
        if (!changed) return
        lastPoints = points
        // 레이아웃 전(크기 0)에는 BoundingBox 줌이 무의미하므로 레이아웃 이후로 미룬다.
        map.post { fit(map, points) }
    }

    private fun fit(map: MapView, points: List<GeoPoint>) {
        if (points.size == 1) {
            map.controller.setZoom(17.0)
            map.controller.animateTo(OsmPoint(points[0].lat, points[0].lng))
            return
        }
        val padded = points.flatMap { listOf(offsetPoint(it, 0.0, 80.0), offsetPoint(it, 180.0, 80.0), offsetPoint(it, 90.0, 80.0), offsetPoint(it, 270.0, 80.0)) }
        val box = BoundingBox.fromGeoPoints(padded.map { OsmPoint(it.lat, it.lng) })
        map.zoomToBoundingBox(box, true, (24 * map.resources.displayMetrics.density).toInt())
    }
}

/** 동승자 정확도 원 → 경로선 → 픽업 마커 → 동승자 점 → 차량 순으로 그린다. */
private class MajungOverlay(private val density: Float) : Overlay() {
    var state: LiveMapState = LiveMapState()
    var palette: MapPalette? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val chevron = Path()
    private val tmp = Point()

    private fun dp(v: Float) = v * density

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pal = palette ?: return
        val proj = mapView.projection
        val car = state.car?.let { px(proj, it) }
        val passenger = state.passenger?.let { px(proj, it) }
        val pickup = state.pickup?.let { px(proj, it) }

        if (passenger != null) drawAccuracyCircle(canvas, proj, passenger, pal)
        if (car != null && pickup != null) drawRoute(canvas, car, pickup, pal)
        if (pickup != null) drawPickup(canvas, pickup, pal)
        if (passenger != null) drawPassenger(canvas, passenger, pal)
        if (car != null) drawCar(canvas, car, state.carHeadingDeg ?: 0.0, pal)
    }

    private fun px(proj: Projection, p: GeoPoint): FloatArray {
        proj.toPixels(OsmPoint(p.lat, p.lng), tmp)
        return floatArrayOf(tmp.x.toFloat(), tmp.y.toFloat())
    }

    // IVI(2560×1440, density 1)에서 운전석 거리에서도 보이도록 폰 기준의 두 배 크기로 그린다.
    private fun drawAccuracyCircle(canvas: Canvas, proj: Projection, c: FloatArray, pal: MapPalette) {
        val r = state.passengerAccuracyM?.let { proj.metersToPixels(it.toFloat()) } ?: return
        if (r < dp(16f)) return
        fill.color = pal.passenger; fill.alpha = 36
        canvas.drawCircle(c[0], c[1], r, fill)
        stroke.color = pal.passengerRing; stroke.alpha = 140; stroke.strokeWidth = dp(2f); stroke.pathEffect = null
        canvas.drawCircle(c[0], c[1], r, stroke)
        fill.alpha = 255; stroke.alpha = 255
    }

    private fun drawRoute(canvas: Canvas, a: FloatArray, b: FloatArray, pal: MapPalette) {
        stroke.color = pal.route; stroke.strokeWidth = dp(6f)
        stroke.pathEffect = DashPathEffect(floatArrayOf(dp(20f), dp(16f)), 0f)
        canvas.drawLine(a[0], a[1], b[0], b[1], stroke)
        stroke.pathEffect = null
    }

    private fun drawPickup(canvas: Canvas, c: FloatArray, pal: MapPalette) {
        val half = dp(26f)  // 52 dp 판
        val corner = dp(10f)
        fill.color = pal.pickup
        canvas.drawRoundRect(c[0] - half, c[1] - half, c[0] + half, c[1] + half, corner, corner, fill)
        stroke.color = pal.pickupInk; stroke.strokeWidth = dp(4f)
        canvas.drawRoundRect(c[0] - half, c[1] - half, c[0] + half, c[1] + half, corner, corner, stroke)
    }

    private fun drawPassenger(canvas: Canvas, c: FloatArray, pal: MapPalette) {
        val r = dp(14f)  // 28 dp 점
        fill.color = pal.passenger
        canvas.drawCircle(c[0], c[1], r, fill)
        stroke.color = pal.passengerRing; stroke.strokeWidth = dp(5f)
        canvas.drawCircle(c[0], c[1], r, stroke)
    }

    private fun drawCar(canvas: Canvas, c: FloatArray, headingDeg: Double, pal: MapPalette) {
        val disc = dp(30f)
        fill.color = pal.carRing
        canvas.drawCircle(c[0], c[1], disc, fill)
        stroke.color = pal.car; stroke.strokeWidth = dp(4f); stroke.pathEffect = null
        canvas.drawCircle(c[0], c[1], disc, stroke)
        // 44 dp 쉐브론, 진행 방향(북=0°, 시계 방향)으로 회전
        val h = dp(22f)
        chevron.reset()
        chevron.moveTo(0f, -h)
        chevron.lineTo(h * 0.8f, h)
        chevron.lineTo(0f, h * 0.45f)
        chevron.lineTo(-h * 0.8f, h)
        chevron.close()
        canvas.save()
        canvas.translate(c[0], c[1])
        canvas.rotate(headingDeg.toFloat())
        fill.color = pal.car
        canvas.drawPath(chevron, fill)
        canvas.restore()
    }
}
