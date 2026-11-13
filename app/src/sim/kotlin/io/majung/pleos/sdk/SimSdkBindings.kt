package io.majung.pleos.sdk

import android.content.Context
import io.majung.pleos.audio.AndroidPlatformTtsAdapter
import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.IntBounds
import io.majung.pleos.model.SeatZone
import io.majung.pleos.model.VehicleCapabilityMatrix
import io.majung.pleos.orchestrator.NaviRouteGateway
import io.majung.pleos.orchestrator.NaviRouteSlice
import io.majung.pleos.runtime.MajungRuntimeBridge
import io.majung.pleos.runtime.VehicleLocationReading
import io.majung.pleos.vehicle.LowLevelVehicleHardware
import io.majung.pleos.vehicle.VehicleTelemetryListener
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * [sim Flavor 전용] 외부 Pleos Maven 접근 권한이나 실차 없이도 일반 Android Studio 에뮬레이터/유닛 테스트에서
 * 전체 6단계 픽업 흐름을 동일 인터페이스로 즉시 빌드·실행할 수 있는 시뮬레이션 구현체
 */
class SimVehicleHardware(
    var simulateEntryTrimWithoutRow2Occupancy: Boolean = false
) : LowLevelVehicleHardware {

    private val fanSpeeds = mutableMapOf(SeatZone.ROW_1_LEFT to 2, SeatZone.ROW_1_RIGHT to 2, SeatZone.ROW_2_RIGHT to 2)
    private val seatHeaters = mutableMapOf(SeatZone.ROW_1_LEFT to 0, SeatZone.ROW_1_RIGHT to 1, SeatZone.ROW_2_RIGHT to 2)
    private val hvacTemps = mutableMapOf(SeatZone.ROW_1_LEFT to 22.5f)
    private val ambientColors = mutableMapOf(SeatZone.ROW_1_LEFT to 0xFFFFFFFF.toInt())
    private var recircOn: Boolean = true
    private val listeners = CopyOnWriteArraySet<VehicleTelemetryListener>()

    /** 테스트·시나리오 재생기가 가상 차량 신호를 주입하는 지점 */
    fun emit(block: VehicleTelemetryListener.() -> Unit) = listeners.forEach { it.block() }

    override suspend fun initialize(): Boolean = true

    override suspend fun probeCapabilities(): VehicleCapabilityMatrix {
        return VehicleCapabilityMatrix(
            doorPositionSupported = true,
            doorLockWritable = true,
            windowPositionWritable = true,
            row1RightOccupancySupported = !simulateEntryTrimWithoutRow2Occupancy,
            row2OccupancySupported = !simulateEntryTrimWithoutRow2Occupancy,
            seatBeltBuckledSupported = true,
            seatHeaterSupported = true,
            seatHeaterWritable = true,
            hvacFanSupported = true,
            hvacFanWritable = true,
            hvacFanBoundsByZone = if (simulateEntryTrimWithoutRow2Occupancy) {
                mapOf(SeatZone.ROW_1_LEFT to IntBounds(1, 5))
            } else {
                mapOf(
                    SeatZone.ROW_1_LEFT to IntBounds(1, 7),
                    SeatZone.ROW_1_RIGHT to IntBounds(1, 5),
                    SeatZone.ROW_2_RIGHT to IntBounds(1, 5)
                )
            },
            hvacRecirculationWritable = true,
            hvacTempWritable = true,
            readingLightWritable = true,
            ambientColorSupported = !simulateEntryTrimWithoutRow2Occupancy,
            ambientColorWritable = !simulateEntryTrimWithoutRow2Occupancy,
            steeringAngleSupported = true,
            brakePedalSupported = true,
            wiperStateSupported = true
        )
    }

    override suspend fun readDoorOpen(zone: SeatZone): Boolean = false
    override suspend fun readSeatOccupied(zone: SeatZone): Boolean? =
        if (simulateEntryTrimWithoutRow2Occupancy) null else false
    override suspend fun readSeatBeltBuckled(zone: SeatZone): Boolean = false
    override suspend fun readSeatHeaterLevel(zone: SeatZone): Int? = seatHeaters[zone]
    override suspend fun writeSeatHeaterLevel(zone: SeatZone, level: Int): Boolean {
        seatHeaters[zone] = level
        return true
    }

    override suspend fun readHvacFanSpeed(zone: SeatZone): Int? = fanSpeeds[zone]
    override suspend fun writeHvacFanSpeed(zone: SeatZone, speed: Int): Boolean {
        fanSpeeds[zone] = speed
        return true
    }

    override suspend fun readHvacRecirculationOn(): Boolean = recircOn
    override suspend fun writeHvacRecirculationOn(recirculate: Boolean): Boolean {
        recircOn = recirculate
        return true
    }

    override suspend fun readHvacTempCelsius(zone: SeatZone): Float? = hvacTemps[zone]
    override suspend fun writeHvacTempCelsius(zone: SeatZone, tempC: Float): Boolean {
        hvacTemps[zone] = tempC
        return true
    }

    override suspend fun readAmbientColorArgb(zone: SeatZone): Int? = ambientColors[zone]
    override suspend fun writeAmbientColorArgb(zone: SeatZone, argb32: Int): Boolean {
        ambientColors[zone] = argb32
        return true
    }

    override fun registerTelemetryCallbacks(listener: VehicleTelemetryListener) {
        listeners.add(listener)
    }

    override fun unregisterTelemetryCallbacks(listener: VehicleTelemetryListener) {
        listeners.remove(listener)
    }

    override fun release() {
        listeners.clear()
    }
}

class SimNaviGateway : NaviRouteGateway {
    var lastDispatchedSlice: NaviRouteSlice? = null
        private set

    override suspend fun dispatchRouteWithWaypoints(slice: NaviRouteSlice): Boolean {
        lastDispatchedSlice = slice
        return true
    }

    override suspend fun cancelActiveRoute(): Boolean {
        lastDispatchedSlice = null
        return true
    }
}

/**
 * 학원가 픽업 시나리오(파리바게뜨 대치점 포켓, 37.498812/127.061420 기준)를 실제 신호 순서대로 재생한다.
 * 모든 이벤트는 LowLevelVehicleHardware 리스너를 통해 들어가므로 앱 로직은 pleos 플레이버와 완전히 동일한 경로를 탄다.
 */
class SimDriveScenario(private val hw: SimVehicleHardware, private val onLog: (String) -> Unit) {
    private val stopLat = 37.498812
    private val stopLng = 127.061420
    @Volatile private var running = false

    private fun fix(lat: Double, lng: Double, heading: Double = 0.0, conf: Double = 0.88) = VehicleLocationReading(
        supported = true, latitude = lat, longitude = lng, headingDeg = heading, confidence = conf,
        rawSummary = "[SIM] FusedLocation(${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lng)}, conf=${String.format(Locale.US, "%.2f", conf)})"
    )

    /** 남쪽 300m에서 북상 → 70m에서 우측 방향지시등 → 감속 → 간판 12m 앞 P단 정차 */
    fun playApproachAndStop(onFix: (VehicleLocationReading) -> Unit) {
        if (running) return
        running = true
        thread(name = "majung-sim-drive") {
            val steps = listOf(
                Triple(300, 42f, PleosTurnSignalState.OFF), Triple(240, 40f, PleosTurnSignalState.OFF), Triple(180, 36f, PleosTurnSignalState.OFF),
                Triple(120, 30f, PleosTurnSignalState.OFF), Triple(70, 22f, PleosTurnSignalState.RIGHT), Triple(40, 14f, PleosTurnSignalState.RIGHT),
                Triple(20, 7f, PleosTurnSignalState.RIGHT), Triple(12, 2f, PleosTurnSignalState.RIGHT)
            )
            // 저녁 학원가: 하향등 ON, 경로 안내 중(다음 회전 = 정차 포켓 진입 우회전)
            hw.emit { onGearChanged(GearPosition.D); onBrakePedalChanged(false); onLowBeamChanged(true) }
            onLog("[SIM] 주행 시작 · 정차 지점 남쪽 300m · 하향등 ON")
            for ((south, speed, turn) in steps) {
                if (!running) return@thread
                val lat = stopLat - south / 111_320.0
                hw.emit {
                    onSpeedChanged(speed); onTurnSignalChanged(turn); onBrakePedalChanged(speed < 25f)
                    onNextTurnDistanceChanged(if (south > 20) south - 10 else Int.MAX_VALUE)
                }
                onFix(fix(lat, stopLng))
                Thread.sleep(1_000)
            }
            hw.emit { onSpeedChanged(0f); onBrakePedalChanged(true); onNextTurnDistanceChanged(Int.MAX_VALUE) }
            onFix(fix(stopLat + 12 / 111_320.0, stopLng))
            Thread.sleep(600)
            hw.emit { onGearChanged(GearPosition.P) }
            onLog("[SIM] 간판 +12m 지점 P단 정차")
            running = false
        }
    }

    /** 운전자 승차 맞이 후: 조수석 문 열림 → 착좌 → 문 닫힘 → 벨트 체결 */
    fun playPassengerBoarding() {
        thread(name = "majung-sim-boarding") {
            val z = SeatZone.ROW_1_RIGHT
            Thread.sleep(800); hw.emit { onDoorStateChanged(z, true, System.currentTimeMillis()) }; onLog("[SIM] 조수석 문 열림")
            Thread.sleep(2_200); hw.emit { onSeatOccupancyChanged(z, true) }; onLog("[SIM] 조수석 착좌")
            Thread.sleep(2_200); hw.emit { onDoorStateChanged(z, false, System.currentTimeMillis()) }; onLog("[SIM] 조수석 문 닫힘")
            Thread.sleep(1_500); hw.emit { onSeatBeltChanged(z, true, System.currentTimeMillis()) }; onLog("[SIM] 안전벨트 체결")
        }
    }

    /** 오안내·안전 데모: 뒷좌석 차도 쪽 문이 먼저 열리는 경우 */
    fun playTrafficSideDoorMistake() {
        thread(name = "majung-sim-wrongdoor") {
            hw.emit { onDoorStateChanged(SeatZone.ROW_2_LEFT, true, System.currentTimeMillis()) }
            Thread.sleep(2_500)
            hw.emit { onDoorStateChanged(SeatZone.ROW_2_LEFT, false, System.currentTimeMillis()) }
        }
    }
}

class SimRuntimeBridgeImpl(context: Context) : MajungRuntimeBridge {
    private val simHardware = SimVehicleHardware()
    private val simNavi = SimNaviGateway()
    private val simTts = AndroidPlatformTtsAdapter(context)
    private var scenarioLog: (String) -> Unit = {}
    private val scenario = SimDriveScenario(simHardware) { scenarioLog(it) }
    private var locationSink: ((VehicleLocationReading) -> Unit)? = null

    private var simDoorLeftPos = 0
    private var simDoorUnlocked = false
    private var simWindowPct = 0
    private var simReadingLightOn = false
    private var simSeatOccupied = false

    override val runtimeBadgeLabel: String = "[SIM 시뮬레이션]"
    override val usesRealPleosSdk: Boolean = false
    override val lowLevelHardware: LowLevelVehicleHardware = simHardware
    override val naviGateway: NaviRouteGateway = simNavi
    override val ttsEngine: PleosTtsEngine = simTts

    override fun initialize(onInitializedLog: (String) -> Unit) {
        onInitializedLog("[SIM 시뮬레이션] 인메모리 Vehicle · Navi · Location · Android TTS 초기화 완료")
    }

    override fun queryStep5FrontLeftDoorPosition(onResult: (pos: Int?, logLine: String) -> Unit) {
        onResult(simDoorLeftPos, "[SIM Step 5] getCurrentDoorPosition(ROW_1_LEFT) -> pos=$simDoorLeftPos")
    }

    override fun queryVehicleVin(onResult: (vin: String?, logLine: String) -> Unit) {
        onResult("KMHMAJUNG2026SIM1", "[SIM] getVINInfo() -> KMHMAJUNG2026SIM1 (아이오닉 패밀리)")
    }

    override fun queryLastKnownVehicleLocation(onResult: (VehicleLocationReading) -> Unit) {
        onResult(
            VehicleLocationReading(
                supported = true, latitude = 37.498920, longitude = 127.061420, headingDeg = 0.0, confidence = 0.88,
                rawSummary = "[SIM] FusedLocation(37.49892, 127.06142, conf=0.88)"
            )
        )
    }

    override fun startLocationUpdates(onFix: (VehicleLocationReading) -> Unit) {
        locationSink = onFix
    }

    override fun stopLocationUpdates() {
        locationSink = null
    }

    override val supportsSimulatedScenario: Boolean = true

    override fun playSimulatedApproachAndStop(onLog: (String) -> Unit) {
        scenarioLog = onLog
        scenario.playApproachAndStop { fix -> locationSink?.invoke(fix) }
    }

    override fun playSimulatedPassengerBoarding(onLog: (String) -> Unit) {
        scenarioLog = onLog
        scenario.playPassengerBoarding()
    }

    override fun playSimulatedTrafficSideDoorMistake(onLog: (String) -> Unit) {
        scenarioLog = onLog
        onLog("[SIM] 뒷좌석 차도 쪽(ROW_2_LEFT) 문 열림 주입")
        scenario.playTrafficSideDoorMistake()
    }

    override fun queryTurnSignalState(onResult: (PleosTurnSignalState, String) -> Unit) {
        onResult(PleosTurnSignalState.RIGHT, "[SIM] TurnSignal -> RIGHT")
    }

    override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit) {
        simDoorUnlocked = unlocked
        onResult(simDoorUnlocked, "[SIM] setDoorLock(ROW_1_RIGHT, value=${!unlocked}) -> unlocked=$simDoorUnlocked")
    }

    override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit) {
        simWindowPct = targetPercent.coerceIn(0, 100)
        onResult(simWindowPct, "[SIM] setWindowPosition(ROW_1_RIGHT, $simWindowPct%) 완료")
    }

    override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit) {
        simReadingLightOn = enabled
        onResult(simReadingLightOn, "[SIM] setReadingLightSwitch(ROW_1_RIGHT, ${if (enabled) "ON" else "OFF"}) 완료")
    }

    override fun queryPassengerSeatOccupancy(useRearRight: Boolean, onResult: (Boolean?, String) -> Unit) {
        simSeatOccupied = true
        onResult(simSeatOccupied, "[SIM] getCurrentSeatOccupancy -> OCCUPIED(true)")
    }

    override fun release() {
        locationSink = null
        simTts.release()
        simHardware.release()
    }
}
