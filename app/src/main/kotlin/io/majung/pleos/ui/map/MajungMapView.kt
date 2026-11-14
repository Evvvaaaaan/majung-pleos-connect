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
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
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

/** ARGB color ints. 화면 디자인 쪽에서 채운다. pickup = 승차 지점 판, pickupInk = 판 위 현대 엠블럼과 테두리. */
data class MapPalette(
    val car: Int, val carRing: Int,
    val passenger: Int, val passengerRing: Int,
    val pickup: Int, val pickupInk: Int,
    val route: Int
)

data class MapTheme(val dark: Boolean, val palette: MapPalette)

private const val ATTRIBUTION = "© OpenStreetMap contributors"
private val DEFAULT_CENTER = OsmPoint(37.4988, 127.0614)

/** 승차 지점 마커의 현대 엠블럼 (벡터 출처: Wikimedia Commons "Hyundai Motor Company logo.svg", 공개 도메인·상표). 원본 좌표계 93.78 × 47.77. */
private const val EMBLEM_PATH = "m 56.707,29.416 c 0.714,3.69 -1.22,6.755 -2.827,9.73 -1.785,2.768 -4.731,5.147 -8.272,4.791 -7.946,-0.119 -15.712,-1.339 -22.914,-3.512 -0.327,-0.089 -0.654,-0.298 -0.833,-0.595 -0.149,-0.417 0.06,-0.804 0.357,-1.071 6.577,-5.327 14.403,-7.796 22.14,-10.386 2.886,-0.833 5.952,-1.726 9.195,-1.309 1.338,0.179 2.677,1.102 3.154,2.352 z M 83.816,13.645 c 3.69,3.035 7.023,7.172 5.892,12.171 -1.845,7.38 -9.879,11.308 -16.277,13.807 -3.482,1.161 -6.993,2.352 -10.802,2.709 -0.238,-0.03 -0.625,0.029 -0.685,-0.328 l 0.089,-0.416 c 5.416,-6.101 9.522,-12.944 13.124,-19.878 1.666,-3.125 3.214,-6.338 4.642,-9.493 0.208,-0.238 0.417,-0.357 0.655,-0.446 1.338,0.118 2.291,1.16 3.362,1.874 z M 31.888,5.789 31.769,6.235 C 23.943,15.132 18.795,25.458 13.915,35.724 12.963,36.617 12.04,35.426 11.177,35.039 6.832,32.183 2.904,27.57 4.035,22.095 5.85,14.954 13.2,11.145 19.331,8.557 c 3.63,-1.369 7.41,-2.47 11.397,-3.095 0.417,-10e-4 0.952,-0.06 1.16,0.327 z m 38.835,1.428 c 0.446,0.238 0.982,0.327 1.25,0.773 0.238,0.506 -0.238,0.833 -0.506,1.161 -6.16,4.82 -13.212,7.499 -20.414,9.85 -3.661,0.893 -7.351,2.5 -11.427,1.607 -0.953,-0.238 -1.815,-0.863 -2.351,-1.786 -1.012,-2.797 0.357,-5.713 1.547,-8.153 1.577,-3.065 4.375,-6.666 8.243,-6.725 8.332,-0.06 16.188,1.19 23.658,3.273 z M 81.019,7.455 C 87.328,10.907 94.47,16.977 93.726,25.071 92.863,32.57 85.87,37.807 79.621,40.962 61.379,49.681 35.758,49.948 16.921,42.331 10.552,39.683 3.41,35.13 0.731,28.285 -1.322,22.661 1.177,16.799 5.374,12.901 14.152,4.926 25.341,2.189 36.858,0.611 c 12.499,-1.518 25.651,-0.179 36.84,3.66 2.5,0.923 4.97,1.935 7.321,3.184 z"
private const val EMBLEM_W = 93.78f
private const val EMBLEM_H = 47.77f

/** 마중 심볼 '파란 판에 새긴 ㅈ' (docs/brand/majung-symbol.svg): 72 그리드, 획 9. 동승자가 고른 자리(수락 전)에 그린다. */
private const val SYMBOL_PATH = "M19 21h34M36 25.5 23.5 51M36 25.5 48.5 51"
private const val SYMBOL_W = 72f

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
        val points = listOfNotNull(state.car, state.passenger, state.pickup, state.proposed)
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
    // 원본 SVG 의 fill-rule=evenodd: 타원 안의 네 조각이 H 와 테두리 사이의 빈 곳(구멍)이다.
    private val emblem: Path by lazy { PathParser().parsePathString(EMBLEM_PATH).toPath().asAndroidPath().apply { fillType = Path.FillType.EVEN_ODD } }
    private val symbol: Path by lazy { PathParser().parsePathString(SYMBOL_PATH).toPath().asAndroidPath() }
    private val tmp = Point()

    private fun dp(v: Float) = v * density

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val pal = palette ?: return
        val proj = mapView.projection
        val car = state.car?.let { px(proj, it) }
        val passenger = state.passenger?.let { px(proj, it) }
        val pickup = state.pickup?.let { px(proj, it) }
        val proposed = state.proposed?.let { px(proj, it) }

        if (passenger != null) drawAccuracyCircle(canvas, proj, passenger, pal)
        if (car != null && pickup != null) drawRoute(canvas, car, pickup, pal)
        if (pickup != null) drawPickup(canvas, pickup, pal)
        if (proposed != null) drawProposed(canvas, proposed, pal)
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

    /** 승차 지점: 흰 판(88×56 dp) 위 현대 엠블럼(너비 72 dp). */
    private fun drawPickup(canvas: Canvas, c: FloatArray, pal: MapPalette) {
        val hw = dp(44f)
        val hh = dp(28f)
        val corner = dp(10f)
        fill.color = pal.pickup
        canvas.drawRoundRect(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh, corner, corner, fill)
        stroke.color = pal.pickupInk; stroke.strokeWidth = dp(2f)
        canvas.drawRoundRect(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh, corner, corner, stroke)
        val s = dp(72f) / EMBLEM_W
        canvas.save()
        canvas.translate(c[0] - EMBLEM_W * s / 2f, c[1] - EMBLEM_H * s / 2f)
        canvas.scale(s, s)
        fill.color = pal.pickupInk
        canvas.drawPath(emblem, fill)
        canvas.restore()
    }

    /** 동승자가 고른 자리(수락 전): 마중 심볼 48 dp — 현대 블루 판 위 흰 ㅈ. 승차 지점(현대 엠블럼)과 구분된다. */
    private fun drawProposed(canvas: Canvas, c: FloatArray, pal: MapPalette) {
        val half = dp(24f)
        val corner = dp(12f)
        fill.color = pal.pickupInk
        canvas.drawRoundRect(c[0] - half, c[1] - half, c[0] + half, c[1] + half, corner, corner, fill)
        val s = (half * 2f) / SYMBOL_W
        stroke.color = pal.pickup; stroke.strokeWidth = 9f * s; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        canvas.save()
        canvas.translate(c[0] - half, c[1] - half)
        canvas.scale(s, s)
        canvas.drawPath(symbol, stroke)
        canvas.restore()
        stroke.strokeCap = Paint.Cap.BUTT; stroke.strokeJoin = Paint.Join.MITER
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
