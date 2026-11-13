package io.majung.pleos.sdk

import android.content.Context
import android.speech.tts.TextToSpeech as AndroidTts
import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.finder.PleosTurnSignalState
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
    private val listeners = mutableSetOf<VehicleTelemetryListener>()

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

class SimAndroidTtsAdapter(context: Context) : PleosTtsEngine {
    private var tts: AndroidTts? = null

    init {
        runCatching {
            tts = AndroidTts(context.applicationContext) { status ->
                if (status == AndroidTts.SUCCESS) {
                    tts?.language = Locale.KOREAN
                }
            }
        }
    }

    override fun initializeAndSetKoreanLocale(onReady: () -> Unit) {
        onReady()
    }

    override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            tts?.speak(text, AndroidTts.QUEUE_FLUSH, null, "majung_sim_utt")
            onCompleted()
        }.onFailure { e ->
            onError(e.message ?: "Android TTS unavailable")
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}

class SimRuntimeBridgeImpl(context: Context) : MajungRuntimeBridge {
    private val simHardware = SimVehicleHardware()
    private val simNavi = SimNaviGateway()
    private val simTts = SimAndroidTtsAdapter(context)

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
        // 기본 한빛약국·파리바게뜨 후보 지점(37.498812, 127.061420) 대비 약 +12m 전방 정차 시뮬레이션 좌표
        onResult(
            VehicleLocationReading(
                supported = true,
                latitude = 37.498920,
                longitude = 127.061420,
                headingDeg = 0.0,
                confidence = 0.88,
                rawSummary = "[SIM] FusedLocation(37.49892, 127.06142, conf=0.88)"
            )
        )
    }

    override fun queryTurnSignalState(onResult: (PleosTurnSignalState, String) -> Unit) {
        onResult(PleosTurnSignalState.RIGHT, "[SIM] TurnSignal -> RIGHT (반경 80m 이내 감속 복합 조건 시뮬레이션)")
    }

    override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit) {
        simDoorUnlocked = unlocked
        val rawLockValue = !unlocked
        onResult(simDoorUnlocked, "[SIM] setDoorLock(ROW_1_RIGHT, value=$rawLockValue) -> unlocked=$simDoorUnlocked")
    }

    override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit) {
        simWindowPct = targetPercent.coerceIn(0, 100)
        onResult(simWindowPct, "[SIM] setWindowPosition(ROW_1_RIGHT, $simWindowPct%) 완료")
    }

    override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit) {
        simReadingLightOn = enabled
        onResult(simReadingLightOn, "[SIM] setCabinLightSwitch(${if (enabled) "ON" else "OFF"}) 완료")
    }

    override fun queryPassengerSeatOccupancy(useRearRight: Boolean, onResult: (Boolean?, String) -> Unit) {
        simSeatOccupied = true
        onResult(simSeatOccupied, "[SIM] getCurrentSeatOccupancy -> OCCUPIED(true)")
    }

    override fun release() {
        simTts.release()
        simHardware.release()
    }
}
