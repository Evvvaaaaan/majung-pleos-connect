package io.majung.pleos.sdk

import android.content.Context
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [pleos Flavor 전용] 공식 Pleos Connect SDK 실연동 구현체
 * - Vehicle 2.3.2 / NaviHelper 3.0.5 / FusedLocation 1.1.3 / TextToSpeech 3.3.1 (Mode.ON_DEVICE)
 *   (nexus-playground.pleos.ai maven-metadata 기준 실제 배포 버전)
 */
class PleosOnDeviceWelcomeTtsAdapter(
    context: Context,
    useHybridServerMode: Boolean = false
) : PleosTtsEngine {
    private val mode = if (useHybridServerMode) Mode.HYBRID else Mode.ON_DEVICE
    private val textToSpeech = TextToSpeech(context.applicationContext, mode)

    override fun initializeAndSetKoreanLocale(onReady: () -> Unit) {
        runCatching {
            textToSpeech.initialize()
            textToSpeech.setLocale(Locale.KOREAN)
        }
        onReady()
    }

    override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) {
        runCatching {
            textToSpeech.speak(text, true)
            onCompleted()
        }.onFailure { e ->
            onError(e.message ?: "Pleos TextToSpeech synthesis failed")
        }
    }

    override fun stop() {
        runCatching { textToSpeech.stop() }
    }

    override fun release() {
        runCatching { textToSpeech.release() }
    }
}

class PleosNaviSdkGateway(context: Context) : NaviRouteGateway {
    private val naviHelper = NaviHelper(context.applicationContext)

    fun initialize() {
        runCatching { naviHelper.initialize() }
    }

    override suspend fun dispatchRouteWithWaypoints(slice: NaviRouteSlice): Boolean = runCatching {
        val destination = RouteInfo(
            slice.finalDestination.latitude,
            slice.finalDestination.longitude,
            null,
            null,
            slice.finalDestination.pitstopName,
            slice.finalDestination.poiId,
            slice.finalDestination.address,
            "0"
        )

        val waypoints = buildList {
            slice.firstWaypoint?.let { wp1 ->
                add(RequestWaypointInfo(wp1.latitude, wp1.longitude, wp1.pitstopName, WaypointIndex.FIRST, wp1.poiId, wp1.address, "0"))
            }
            slice.secondWaypoint?.let { wp2 ->
                add(RequestWaypointInfo(wp2.latitude, wp2.longitude, wp2.pitstopName, WaypointIndex.SECOND, wp2.poiId, wp2.address, "0"))
            }
        }

        if (waypoints.isEmpty()) {
            naviHelper.requestRoute(destination)
        } else {
            naviHelper.requestRouteWithWaypoints(RouteWithWaypointsRequest(destination, waypoints))
        }
        true
    }.getOrDefault(false)

    override suspend fun cancelActiveRoute(): Boolean = runCatching {
        naviHelper.cancelRoute()
        true
    }.getOrDefault(false)

    fun release() {
        runCatching { naviHelper.release() }
    }
}

private fun SeatZone.toDoorValue(): DoorValue = when (this) {
    SeatZone.ROW_1_LEFT -> DoorValue.ROW_1_LEFT
    SeatZone.ROW_1_RIGHT -> DoorValue.ROW_1_RIGHT
    SeatZone.ROW_2_LEFT -> DoorValue.ROW_2_LEFT
    SeatZone.ROW_2_RIGHT -> DoorValue.ROW_2_RIGHT
}

private fun SeatZone.toSeatValue(): SeatValue = when (this) {
    SeatZone.ROW_1_LEFT -> SeatValue.ROW_1_LEFT
    SeatZone.ROW_1_RIGHT -> SeatValue.ROW_1_RIGHT
    SeatZone.ROW_2_LEFT -> SeatValue.ROW_2_LEFT
    SeatZone.ROW_2_RIGHT -> SeatValue.ROW_2_RIGHT
}

/**
 * Vehicle SDK 읽기/쓰기 + `register*` 실시간 콜백 어댑터.
 * registerTelemetryCallbacks()가 호출되면 기어·브레이크·조향각·와이퍼·방향지시등·도어(4)·착좌(2)·벨트(2)를 SDK에 실제로 등록한다.
 */
class PleosLowLevelVehicleAdapter(private val vehicle: Vehicle) : LowLevelVehicleHardware {
    private val listeners = CopyOnWriteArraySet<VehicleTelemetryListener>()
    private var sdkRegistered = false

    private inline fun fanOut(block: VehicleTelemetryListener.() -> Unit) = listeners.forEach { runCatching { it.block() } }
    private fun log(line: String) = fanOut { onTelemetrySourceLog(line) }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            val mapped = when (gear) {
                VehicleGear.GEAR_PARK -> GearPosition.P
                VehicleGear.GEAR_REVERSE -> GearPosition.R
                VehicleGear.GEAR_NEUTRAL -> GearPosition.N
                null, VehicleGear.GEAR_UNKNOWN -> return
                else -> GearPosition.D
            }
            fanOut { onGearChanged(mapped) }
        }
        override fun onFailed(e: Exception) = log("gear 콜백 실패: ${e.message}")
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { status?.let { v -> fanOut { onBrakePedalChanged(v) } } }
        override fun onFailed(e: Exception) = log("brake 콜백 실패: ${e.message}")
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { a -> fanOut { onSteeringAngleChanged(a, 0f) } } }
        override fun onFailed(e: Exception) = log("steering 콜백 실패: ${e.message}")
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) { fanOut { onWiperHighChanged(state == WiperState.ON) } }
        override fun onFailed(e: Exception) = log("wiper 콜백 실패: ${e.message}")
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            val mapped = when (value) {
                TurnSignalValue.LEFT -> PleosTurnSignalState.LEFT
                TurnSignalValue.RIGHT -> PleosTurnSignalState.RIGHT
                else -> PleosTurnSignalState.OFF
            }
            fanOut { onTurnSignalChanged(mapped) }
        }
        override fun onFailed(e: Exception) = log("turnSignal 콜백 실패: ${e.message}")
    }
    private val doorListeners: Map<SeatZone, DoorPositionListener> = SeatZone.entries.associateWith { zone ->
        object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) { pos?.let { p -> fanOut { onDoorStateChanged(zone, p > 0, System.currentTimeMillis()) } } }
            override fun onFailed(e: Exception) = log("door[$zone] 콜백 실패: ${e.message}")
        }
    }
    private val seatZones = listOf(SeatZone.ROW_1_RIGHT, SeatZone.ROW_2_RIGHT, SeatZone.ROW_2_LEFT)
    private val occupancyListeners: Map<SeatZone, SeatOccupancyListener> = seatZones.associateWith { zone ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(value: Boolean?) { value?.let { v -> fanOut { onSeatOccupancyChanged(zone, v) } } }
            override fun onFailed(e: Exception) = log("seat[$zone] 콜백 실패: ${e.message}")
        }
    }
    private val beltListeners: Map<SeatZone, SeatBeltBuckledStateListener> = seatZones.associateWith { zone ->
        object : SeatBeltBuckledStateListener {
            override fun onSeatBeltBuckledStateUpdated(state: Boolean?) { state?.let { v -> fanOut { onSeatBeltChanged(zone, v, System.currentTimeMillis()) } } }
            override fun onFailed(e: Exception) = log("belt[$zone] 콜백 실패: ${e.message}")
        }
    }

    override suspend fun initialize(): Boolean = runCatching {
        vehicle.initialize()
        true
    }.getOrDefault(false)

    override suspend fun probeCapabilities(): VehicleCapabilityMatrix {
        val beltAreas = suspendCancellableCoroutine<Int> { cont ->
            runCatching {
                vehicle.getSeat().checkSeatBeltBuckledCapabilities({ areas: List<SeatArea> -> cont.resume(areas.size) }, { cont.resume(0) })
            }.onFailure { cont.resume(0) }
        }
        val occupancyAreas = suspendCancellableCoroutine<Int> { cont ->
            runCatching {
                vehicle.getSeat().checkSeatOccupancyCapabilities({ areas: List<SeatArea> -> cont.resume(areas.size) }, { cont.resume(0) })
            }.onFailure { cont.resume(0) }
        }
        val doorPositionAreas = suspendCancellableCoroutine<Int> { cont ->
            runCatching {
                vehicle.getDoor().checkDoorPositionCapabilities({ m: Map<DoorArea, *> -> cont.resume(m.size) }, { cont.resume(0) })
            }.onFailure { cont.resume(0) }
        }
        val doorLockAreas = suspendCancellableCoroutine<Int> { cont ->
            runCatching {
                vehicle.getDoor().checkDoorLockCapabilities({ areas: List<DoorArea> -> cont.resume(areas.size) }, { cont.resume(0) })
            }.onFailure { cont.resume(0) }
        }
        val windowAreas = suspendCancellableCoroutine<Int> { cont ->
            runCatching {
                vehicle.getWindow().checkWindowPositionCapabilities({ m: Map<WindowArea, *> -> cont.resume(m.size) }, { cont.resume(0) })
            }.onFailure { cont.resume(0) }
        }
        log("capabilities: belt=$beltAreas occupancy=$occupancyAreas doorPos=$doorPositionAreas doorLock=$doorLockAreas window=$windowAreas")
        return VehicleCapabilityMatrix(
            doorPositionSupported = doorPositionAreas > 0,
            doorLockWritable = doorLockAreas > 0,
            windowPositionWritable = windowAreas > 0,
            row1RightOccupancySupported = occupancyAreas > 0,
            row2OccupancySupported = occupancyAreas > 1,
            seatBeltBuckledSupported = beltAreas > 0,
            hvacFanSupported = false,
            hvacFanWritable = false,
            hvacFanBoundsByZone = mapOf(SeatZone.ROW_1_LEFT to IntBounds(1, 5)),
            readingLightWritable = true,
            steeringAngleSupported = true,
            brakePedalSupported = true,
            wiperStateSupported = true
        )
    }

    override suspend fun readDoorOpen(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        runCatching {
            vehicle.getDoor().getCurrentDoorPosition(DoorArea(zone.toDoorValue()), { pos -> cont.resume((pos ?: 0) > 0) }, { cont.resume(null) })
        }.onFailure { cont.resume(null) }
    }

    override suspend fun readSeatOccupied(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        runCatching {
            vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(zone.toSeatValue()), { occupied -> cont.resume(occupied) }, { cont.resume(null) })
        }.onFailure { cont.resume(null) }
    }

    override suspend fun readSeatBeltBuckled(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        runCatching {
            vehicle.getSeat().getCurrentSeatBeltBuckledState(SeatArea(zone.toSeatValue()), { buckled -> cont.resume(buckled) }, { cont.resume(null) })
        }.onFailure { cont.resume(null) }
    }

    // 공조·앰비언트는 이번 범위에서 제어하지 않는다(미지원 → null/false 반환, 게이트웨이가 건너뜀).
    override suspend fun readSeatHeaterLevel(zone: SeatZone): Int? = null
    override suspend fun writeSeatHeaterLevel(zone: SeatZone, level: Int): Boolean = false
    override suspend fun readHvacFanSpeed(zone: SeatZone): Int? = null
    override suspend fun writeHvacFanSpeed(zone: SeatZone, speed: Int): Boolean = false
    override suspend fun readHvacRecirculationOn(): Boolean? = null
    override suspend fun writeHvacRecirculationOn(recirculate: Boolean): Boolean = false
    override suspend fun readHvacTempCelsius(zone: SeatZone): Float? = null
    override suspend fun writeHvacTempCelsius(zone: SeatZone, tempC: Float): Boolean = false
    override suspend fun readAmbientColorArgb(zone: SeatZone): Int? = null
    override suspend fun writeAmbientColorArgb(zone: SeatZone, argb32: Int): Boolean = false

    override fun registerTelemetryCallbacks(listener: VehicleTelemetryListener) {
        listeners.add(listener)
        if (sdkRegistered) return
        sdkRegistered = true
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()
        fun attempt(name: String, block: () -> Unit) = runCatching(block).onSuccess { ok += name }.onFailure { failed += "$name(${it.message})" }

        attempt("gear") { vehicle.getGear().registerGearSelection(gearListener) }
        attempt("brake") { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        attempt("steering") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        attempt("wiper") { vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }
        attempt("turnSignal") { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
        doorListeners.forEach { (zone, l) -> attempt("door[$zone]") { vehicle.getDoor().registerDoorPosition(DoorArea(zone.toDoorValue()), l) } }
        occupancyListeners.forEach { (zone, l) -> attempt("seat[$zone]") { vehicle.getSeat().registerSeatOccupancy(SeatArea(zone.toSeatValue()), l) } }
        beltListeners.forEach { (zone, l) -> attempt("belt[$zone]") { vehicle.getSeat().registerSeatBeltBuckledState(SeatArea(zone.toSeatValue()), l) } }

        // 현재값 1회 조회 → 콜백이 변화 시점에만 오더라도 초기 상태를 확보한다.
        runCatching { vehicle.getGear().getCurrentGearSelectionState({ g -> gearListener.onSelected(g) }, { }) }
        runCatching { vehicle.getTurnSignal().getCurrentTurnSignalState({ v -> turnListener.onTurnSignalStateUpdated(v) }, { }) }
        doorListeners.forEach { (zone, l) ->
            runCatching { vehicle.getDoor().getCurrentDoorPosition(DoorArea(zone.toDoorValue()), { p -> l.onPositionUpdated(p) }, { }) }
        }
        log("Vehicle SDK register* 등록: ${ok.size}건 성공" + (if (failed.isNotEmpty()) " · 실패 ${failed.joinToString()}" else ""))
    }

    override fun unregisterTelemetryCallbacks(listener: VehicleTelemetryListener) {
        listeners.remove(listener)
    }

    override fun release() {
        if (sdkRegistered) {
            runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
            runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
            runCatching { vehicle.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }
            runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
            doorListeners.forEach { (zone, l) -> runCatching { vehicle.getDoor().unregisterDoorPosition(DoorArea(zone.toDoorValue()), l) } }
            occupancyListeners.forEach { (zone, l) -> runCatching { vehicle.getSeat().unregisterSeatOccupancy(SeatArea(zone.toSeatValue()), l) } }
            beltListeners.forEach { (zone, l) -> runCatching { vehicle.getSeat().unregisterSeatBeltBuckledState(SeatArea(zone.toSeatValue()), l) } }
            sdkRegistered = false
        }
        listeners.clear()
    }
}

class PleosRuntimeBridgeImpl(context: Context) : MajungRuntimeBridge {
    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)
    private val fusedLocation = FusedLocation(appContext)
    private val pleosNavi = PleosNaviSdkGateway(appContext)
    private val pleosTts = PleosOnDeviceWelcomeTtsAdapter(appContext, useHybridServerMode = false)
    private var locationInitialized = false
    private var streamingListener: FusedLocationListener? = null

    override val runtimeBadgeLabel: String = "[SDK 실연동]"
    override val usesRealPleosSdk: Boolean = true
    override val lowLevelHardware: LowLevelVehicleHardware = PleosLowLevelVehicleAdapter(vehicle)
    override val naviGateway: NaviRouteGateway = pleosNavi
    override val ttsEngine: PleosTtsEngine = pleosTts

    override fun initialize(onInitializedLog: (String) -> Unit) {
        val statusParts = mutableListOf<String>()
        runCatching {
            vehicle.initialize()
            statusParts.add("Vehicle(2.3.2).initialize() 성공")
        }.onFailure { e ->
            statusParts.add("Vehicle.initialize() 예외: ${e.message}")
        }

        runCatching {
            pleosNavi.initialize()
            statusParts.add("NaviHelper(3.0.5).initialize() 완료")
        }

        runCatching {
            if (fusedLocation.isSupported()) {
                fusedLocation.initialize()
                locationInitialized = true
                statusParts.add("FusedLocation(1.1.3) 지원 확인 및 초기화")
            } else {
                statusParts.add("FusedLocation 미지원 환경 -> 수동 오프셋 폴백 활성")
            }
        }.onFailure { e ->
            statusParts.add("FusedLocation 초기화 폴백: ${e.message}")
        }

        onInitializedLog(statusParts.joinToString(" · "))
    }

    override fun queryStep5FrontLeftDoorPosition(onResult: (pos: Int?, logLine: String) -> Unit) {
        runCatching {
            vehicle.getDoor().getCurrentDoorPosition(
                DoorArea(DoorValue.ROW_1_LEFT),
                { pos: Int? -> onResult(pos, "[Step 5 실호출] door.getCurrentDoorPosition(ROW_1_LEFT) -> onComplete(pos=$pos)") },
                { e: Exception -> onResult(null, "[Step 5 실호출] door.getCurrentDoorPosition 실패 -> ${e.message}") }
            )
        }.onFailure { e ->
            onResult(null, "[Step 5 예외] ${e.message}")
        }
    }

    override fun queryVehicleVin(onResult: (vin: String?, logLine: String) -> Unit) {
        runCatching {
            vehicle.getCarInfo().getVINInfo(
                { vin: String? -> onResult(vin, "carInfo.getVINInfo() -> ${vin ?: "null (사용자 설정 차종 폴백)"}") },
                { e: Exception -> onResult(null, "carInfo.getVINInfo() 폴백 (${e.message})") }
            )
        }.onFailure { e ->
            onResult(null, "carInfo.getVINInfo() 미지원 (${e.message})")
        }
    }

    private fun LocationData?.toReading(prefix: String): VehicleLocationReading {
        if (this == null) {
            return VehicleLocationReading(true, null, null, null, null, "$prefix -> null (저신뢰 텍스트 안내 폴백)")
        }
        val lat = latitude
        val lng = longitude
        val heading = heading.toDouble()
        val conf = confidence.toDouble()
        return VehicleLocationReading(
            supported = true, latitude = lat, longitude = lng, headingDeg = heading, confidence = conf,
            rawSummary = "FusedLocation(${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lng)}, conf=${String.format(Locale.US, "%.2f", conf)})"
        )
    }

    override fun queryLastKnownVehicleLocation(onResult: (VehicleLocationReading) -> Unit) {
        if (!locationInitialized) {
            onResult(VehicleLocationReading(false, null, null, null, null, "FusedLocation.isSupported() == false (수동 간판 오프셋 모드)"))
            return
        }
        runCatching {
            fusedLocation.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) = onResult(location.toReading("getLastKnownLocation()"))
                override fun onError(exception: Exception) =
                    onResult(VehicleLocationReading(true, null, null, null, null, "FusedLocation 오류 폴백: ${exception.message}"))
            })
        }.onFailure { e ->
            onResult(VehicleLocationReading(false, null, null, null, null, "FusedLocation 호출 예외: ${e.message}"))
        }
    }

    override fun startLocationUpdates(onFix: (VehicleLocationReading) -> Unit) {
        if (!locationInitialized || streamingListener != null) return
        val l = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) { if (location != null) onFix(location.toReading("stream")) }
            override fun onError(exception: Exception) = onFix(VehicleLocationReading(true, null, null, null, null, "FusedLocation 스트림 오류: ${exception.message}"))
        }
        runCatching { fusedLocation.registerFusedLocationCallback(l); streamingListener = l }
    }

    override fun stopLocationUpdates() {
        streamingListener?.let { runCatching { fusedLocation.unregisterFusedLocationCallback(it) } }
        streamingListener = null
    }

    override fun queryTurnSignalState(onResult: (PleosTurnSignalState, String) -> Unit) {
        runCatching {
            vehicle.getTurnSignal().getCurrentTurnSignalState(
                { state ->
                    val mapped = when (state) {
                        TurnSignalValue.RIGHT -> PleosTurnSignalState.RIGHT
                        TurnSignalValue.LEFT -> PleosTurnSignalState.LEFT
                        else -> PleosTurnSignalState.OFF
                    }
                    onResult(mapped, "turnSignal.getCurrentTurnSignalState() -> ${state?.name ?: "OFF"}")
                },
                { e -> onResult(PleosTurnSignalState.OFF, "turnSignal 조회 폴백: ${e.message}") }
            )
        }.onFailure { e ->
            onResult(PleosTurnSignalState.OFF, "turnSignal 예외: ${e.message}")
        }
    }

    override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit) {
        // Door.setDoorLock 극성: value = true(잠금), false(잠금 해제)
        val lockArgument = !unlocked
        runCatching {
            vehicle.getDoor().setDoorLock(
                DoorArea(DoorValue.ROW_1_RIGHT),
                lockArgument,
                { _, appliedLockState ->
                    onResult(appliedLockState == false, "door.setDoorLock(ROW_1_RIGHT, value=$lockArgument) -> onComplete(locked=$appliedLockState)")
                },
                { e -> onResult(false, "door.setDoorLock 실패 -> 수동 도어 언락 안내 (${e.message})") }
            )
        }.onFailure { e ->
            onResult(false, "door.setDoorLock 예외 (${e.message})")
        }
    }

    override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit) {
        val clamped = targetPercent.coerceIn(0, 100)
        runCatching {
            vehicle.getWindow().setWindowPosition(
                WindowArea(WindowValue.ROW_1_RIGHT),
                clamped,
                { _, appliedPos ->
                    val actual = appliedPos ?: clamped
                    onResult(actual, "window.setWindowPosition(ROW_1_RIGHT, $clamped) -> onComplete($actual%)")
                },
                { e -> onResult(0, "window.setWindowPosition 실패 (${e.message})") }
            )
        }.onFailure { e ->
            onResult(0, "window.setWindowPosition 예외 (${e.message})")
        }
    }

    override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit) {
        val targetSwitch = if (enabled) LightSwitch.ON else LightSwitch.OFF
        runCatching {
            vehicle.getLight().setReadingLightSwitch(
                SeatArea(SeatValue.ROW_1_RIGHT),
                targetSwitch,
                { applied -> onResult(applied == LightSwitch.ON, "light.setReadingLightSwitch(ROW_1_RIGHT, $targetSwitch) -> onComplete($applied)") },
                { e ->
                    // 독서등 미지원 트림 → 실내등으로 폴백
                    runCatching {
                        vehicle.getLight().setCabinLightSwitch(
                            targetSwitch,
                            { applied -> onResult(applied == LightSwitch.ON, "light.setCabinLightSwitch($targetSwitch) 폴백 -> $applied") },
                            { e2 -> onResult(false, "실내 조명 제어 미지원 (${e.message} / ${e2.message})") }
                        )
                    }.onFailure { onResult(false, "실내 조명 제어 폴백 실패 (${e.message})") }
                }
            )
        }.onFailure { e ->
            onResult(false, "실내 조명 제어 폴백 (${e.message})")
        }
    }

    override fun queryPassengerSeatOccupancy(useRearRight: Boolean, onResult: (Boolean?, String) -> Unit) {
        val seatVal = if (useRearRight) SeatValue.ROW_2_RIGHT else SeatValue.ROW_1_RIGHT
        runCatching {
            vehicle.getSeat().getCurrentSeatOccupancy(
                SeatArea(seatVal),
                { occupied: Boolean? -> onResult(occupied, "seat.getCurrentSeatOccupancy($seatVal) -> onComplete($occupied)") },
                { e: Exception -> onResult(null, "seat.getCurrentSeatOccupancy 미지원 폴백 (${e.message})") }
            )
        }.onFailure { e ->
            onResult(null, "착좌 센서 조회 예외 (${e.message})")
        }
    }

    override fun release() {
        stopLocationUpdates()
        runCatching { pleosTts.release() }
        runCatching { pleosNavi.release() }
        runCatching { lowLevelHardware.release() }
        if (locationInitialized) {
            runCatching { fusedLocation.release() }
            locationInitialized = false
        }
        runCatching { vehicle.release() }
    }
}
