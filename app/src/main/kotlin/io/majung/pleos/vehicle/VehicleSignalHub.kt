package io.majung.pleos.vehicle

import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.SeatZone
import io.majung.pleos.runtime.VehicleLocationReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class SpeedSource { NONE, AAOS_PROPERTY, LOCATION_DERIVED }

/**
 * 현재 차량 신호의 단일 스냅샷. SDK 콜백은 임의의 바인더 스레드에서 오므로 Hub가 동기화해 StateFlow로 내보낸다.
 */
data class VehicleSignalState(
    val gear: GearPosition = GearPosition.P,
    val speedKmh: Float = 0f,
    val speedSource: SpeedSource = SpeedSource.NONE,
    val steeringAngleDeg: Float = 0f,
    val steeringRateDegPerSec: Float = 0f,
    val brakePressed: Boolean = false,
    val wiperOn: Boolean = false,
    val turnSignal: PleosTurnSignalState = PleosTurnSignalState.OFF,
    val doorOpen: Map<SeatZone, Boolean> = emptyMap(),
    val seatOccupied: Map<SeatZone, Boolean> = emptyMap(),
    val beltBuckled: Map<SeatZone, Boolean> = emptyMap(),
    val lowBeamOn: Boolean = false,
    val nextTurnDistanceMeters: Int = Int.MAX_VALUE,
    val location: VehicleLocationReading? = null,
    val lastEventLog: String = "차량 신호 대기 중",
    val eventCount: Int = 0,
    val updatedAtMs: Long = 0L
) {
    /** 폰은 모르는 사실 #1: 차가 실제로 P단에 멈춰 있는가 */
    val stoppedInPark: Boolean get() = gear == GearPosition.P && speedKmh <= 1.0f
    fun isDoorOpen(zone: SeatZone): Boolean = doorOpen[zone] == true
    fun isSeated(zone: SeatZone): Boolean = seatOccupied[zone] == true
    fun isBuckled(zone: SeatZone): Boolean = beltBuckled[zone] == true
}

/**
 * Vehicle SDK / AAOS / FusedLocation 콜백을 하나의 상태로 합치는 허브.
 * - 조향 각속도는 SDK가 주지 않으므로 연속 각도 샘플로 계산한다.
 * - 차속은 AAOS PERF_VEHICLE_SPEED가 우선이며, 3초 이상 끊기면 FusedLocation 연속 좌표로 추정한다.
 */
class VehicleSignalHub(
    private val clock: () -> Long = { System.currentTimeMillis() }
) : VehicleTelemetryListener {

    private val lock = Any()
    private val _state = MutableStateFlow(VehicleSignalState())
    val state: StateFlow<VehicleSignalState> = _state.asStateFlow()

    private var lastSteeringSampleMs: Long? = null
    private var lastAaosSpeedMs: Long? = null
    private var lastFix: VehicleLocationReading? = null
    private var lastFixMs: Long? = null

    override fun onSpeedChanged(speedKmh: Float) = update("speed=${"%.1f".format(speedKmh)}km/h [AAOS]") { s ->
        lastAaosSpeedMs = clock()
        s.copy(speedKmh = speedKmh.coerceAtLeast(0f), speedSource = SpeedSource.AAOS_PROPERTY)
    }

    override fun onGearChanged(gear: GearPosition) = update("gear=$gear") { s ->
        // P단 전환 시 차속 신호가 아직 안 왔더라도 정차로 간주되도록 0으로 수렴시킨다.
        if (gear == GearPosition.P && s.speedSource == SpeedSource.NONE) s.copy(gear = gear, speedKmh = 0f) else s.copy(gear = gear)
    }

    override fun onSteeringAngleChanged(angleDeg: Float, rateDegPerSec: Float) {
        val now = clock()
        update("steering=${angleDeg.toInt()}°") { s ->
            val derivedRate = if (rateDegPerSec != 0f) {
                rateDegPerSec
            } else {
                val prevMs = lastSteeringSampleMs
                if (prevMs != null && now > prevMs) abs(angleDeg - s.steeringAngleDeg) * 1000f / (now - prevMs) else 0f
            }
            lastSteeringSampleMs = now
            s.copy(steeringAngleDeg = angleDeg, steeringRateDegPerSec = derivedRate)
        }
    }

    override fun onBrakePedalChanged(pressed: Boolean) = update("brake=${if (pressed) "ON" else "OFF"}") { it.copy(brakePressed = pressed) }
    override fun onWiperHighChanged(isHighSpeed: Boolean) = update("wiper=${if (isHighSpeed) "ON" else "OFF"}") { it.copy(wiperOn = isHighSpeed) }
    override fun onTurnSignalChanged(state: PleosTurnSignalState) = update("turnSignal=$state") { it.copy(turnSignal = state) }

    override fun onDoorStateChanged(zone: SeatZone, isOpen: Boolean, timestampMs: Long) =
        update("door[$zone]=${if (isOpen) "OPEN" else "CLOSED"}") { it.copy(doorOpen = it.doorOpen + (zone to isOpen)) }

    override fun onSeatOccupancyChanged(zone: SeatZone, isOccupied: Boolean) =
        update("seat[$zone]=${if (isOccupied) "OCCUPIED" else "EMPTY"}") { it.copy(seatOccupied = it.seatOccupied + (zone to isOccupied)) }

    override fun onSeatBeltChanged(zone: SeatZone, isBuckled: Boolean, timestampMs: Long) =
        update("belt[$zone]=${if (isBuckled) "BUCKLED" else "UNBUCKLED"}") { it.copy(beltBuckled = it.beltBuckled + (zone to isBuckled)) }

    override fun onLowBeamChanged(isOn: Boolean) = update("lowBeam=${if (isOn) "ON" else "OFF"}") { it.copy(lowBeamOn = isOn) }

    override fun onNextTurnDistanceChanged(meters: Int) =
        update(if (meters == Int.MAX_VALUE) "tbt=none" else "tbt=${meters}m") { it.copy(nextTurnDistanceMeters = meters) }

    override fun onTelemetrySourceLog(line: String) = update(line) { it }

    /** FusedLocation 연속 수신. AAOS 차속이 없거나 3초 이상 끊긴 경우 좌표 변화로 차속을 추정한다. */
    fun onLocationFix(reading: VehicleLocationReading) {
        val now = clock()
        update(reading.rawSummary) { s ->
            var next = s.copy(location = reading)
            val prev = lastFix
            val prevMs = lastFixMs
            val aaosFresh = lastAaosSpeedMs?.let { now - it <= AAOS_SPEED_STALE_MS } == true
            if (!aaosFresh && prev != null && prevMs != null && now > prevMs &&
                reading.latitude != null && reading.longitude != null && prev.latitude != null && prev.longitude != null
            ) {
                val meters = planarDistanceMeters(prev.latitude, prev.longitude, reading.latitude, reading.longitude)
                val kmh = (meters / ((now - prevMs) / 1000.0)) * 3.6
                next = next.copy(speedKmh = kmh.toFloat().coerceIn(0f, 250f), speedSource = SpeedSource.LOCATION_DERIVED)
            }
            lastFix = reading
            lastFixMs = now
            next
        }
    }

    private fun update(log: String, transform: (VehicleSignalState) -> VehicleSignalState) {
        synchronized(lock) {
            val cur = _state.value
            _state.value = transform(cur).copy(lastEventLog = log, eventCount = cur.eventCount + 1, updatedAtMs = clock())
        }
    }

    companion object {
        const val AAOS_SPEED_STALE_MS = 3_000L

        fun planarDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val mPerDegLat = 111_320.0
            val mPerDegLng = 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
            val dN = (lat2 - lat1) * mPerDegLat
            val dE = (lng2 - lng1) * mPerDegLng
            return sqrt(dN * dN + dE * dE)
        }
    }
}
