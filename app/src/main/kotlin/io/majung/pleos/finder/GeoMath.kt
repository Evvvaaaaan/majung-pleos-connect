package io.majung.pleos.finder

import io.majung.pleos.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// 수십 m 범위에서 쓰는 평면 근사 지오메트리 (CurbsideCarFinderEngine.distanceMeters와 같은 상수).
private const val METERS_PER_DEG_LAT = 111_320.0

/** bearingDeg: 0 = 북, 90 = 동 (시계 방향). */
fun offsetPoint(origin: GeoPoint, bearingDeg: Double, meters: Double): GeoPoint {
    val rad = Math.toRadians(bearingDeg)
    val metersPerDegLng = METERS_PER_DEG_LAT * cos(Math.toRadians(origin.lat))
    return GeoPoint(
        origin.lat + cos(rad) * meters / METERS_PER_DEG_LAT,
        origin.lng + sin(rad) * meters / metersPerDegLng
    )
}

/** from에서 to를 향하는 방위각 0..360 (0 = 북). */
fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val dNorth = (to.lat - from.lat) * METERS_PER_DEG_LAT
    val dEast = (to.lng - from.lng) * METERS_PER_DEG_LAT * cos(Math.toRadians((from.lat + to.lat) / 2.0))
    return (Math.toDegrees(atan2(dEast, dNorth)) + 360.0) % 360.0
}
