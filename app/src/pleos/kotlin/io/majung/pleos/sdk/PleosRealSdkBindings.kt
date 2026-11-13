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
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
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
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [pleos Flavor 전용] 공식 Pleos Connect SDK 실연동 구현체
 * - Getting Started Step 3~5 준수:
 *   Vehicle 2.3.1 / NaviHelper 3.0.5 / FusedLocation 1.1.2 / TextToSpeech 3.3.1 (Mode.ON_DEVICE)
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
            // 공식 가이드: speak(text, true) (isFinal = true)
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
        // 공식 문서(Route requests with waypoints) 8-인자 생성자 규격 준수:
        // RouteInfo(lat, lng, null, null, name, poiId, address, "0")
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
                add(
                    RequestWaypointInfo(
                        wp1.latitude,
                        wp1.longitude,
                        wp1.pitstopName,
                        WaypointIndex.FIRST,
                        wp1.poiId,
                        wp1.address,
                        "0"
                    )
                )
            }
            slice.secondWaypoint?.let { wp2 ->
                add(
                    RequestWaypointInfo(
                        wp2.latitude,
                        wp2.longitude,
                        wp2.pitstopName,
                        WaypointIndex.SECOND,
                        wp2.poiId,
                        wp2.address,
                        "0"
                    )
                )
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

class PleosLowLevelVehicleAdapter(private val vehicle: Vehicle) : LowLevelVehicleHardware {
    private val listeners = mutableSetOf<VehicleTelemetryListener>()

    override suspend fun initialize(): Boolean = runCatching {
        vehicle.initialize()
        true
    }.getOrDefault(false)

    override suspend fun probeCapabilities(): VehicleCapabilityMatrix {
        val beltSupported = suspendCancellableCoroutine<Boolean> { cont ->
            runCatching {
                vehicle.getSeat().checkSeatBeltBuckledCapabilities(
                    onComplete = { _, areas -> cont.resume(areas.isNotEmpty()) },
                    onFailed = { cont.resume(false) }
                )
            }.onFailure { cont.resume(false) }
        }
        return VehicleCapabilityMatrix(
            doorPositionSupported = true,
            doorLockWritable = true,
            windowPositionWritable = true,
            row1RightOccupancySupported = true,
            row2OccupancySupported = true,
            seatBeltBuckledSupported = beltSupported,
            hvacFanSupported = true,
            hvacFanWritable = false,
            hvacFanBoundsByZone = mapOf(SeatZone.ROW_1_LEFT to IntBounds(1, 5)),
            readingLightWritable = true
        )
    }

    override suspend fun readDoorOpen(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        val dv = when (zone) {
            SeatZone.ROW_1_LEFT -> DoorValue.ROW_1_LEFT
            SeatZone.ROW_1_RIGHT -> DoorValue.ROW_1_RIGHT
            SeatZone.ROW_2_LEFT -> DoorValue.ROW_2_LEFT
            SeatZone.ROW_2_RIGHT -> DoorValue.ROW_2_RIGHT
        }
        runCatching {
            vehicle.getDoor().getCurrentDoorPosition(
                area = DoorArea(dv),
                onComplete = { pos -> cont.resume((pos ?: 0) > 0) },
                onFailed = { cont.resume(null) }
            )
        }.onFailure { cont.resume(null) }
    }

    override suspend fun readSeatOccupied(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        val sv = when (zone) {
            SeatZone.ROW_1_LEFT -> SeatValue.ROW_1_LEFT
            SeatZone.ROW_1_RIGHT -> SeatValue.ROW_1_RIGHT
            SeatZone.ROW_2_LEFT -> SeatValue.ROW_2_LEFT
            SeatZone.ROW_2_RIGHT -> SeatValue.ROW_2_RIGHT
        }
        runCatching {
            vehicle.getSeat().getCurrentSeatOccupancy(
                area = SeatArea(sv),
                onComplete = { occupied -> cont.resume(occupied) },
                onFailed = { cont.resume(null) }
            )
        }.onFailure { cont.resume(null) }
    }

    override suspend fun readSeatBeltBuckled(zone: SeatZone): Boolean? = suspendCancellableCoroutine { cont ->
        val sv = when (zone) {
            SeatZone.ROW_1_LEFT -> SeatValue.ROW_1_LEFT
            SeatZone.ROW_1_RIGHT -> SeatValue.ROW_1_RIGHT
            SeatZone.ROW_2_LEFT -> SeatValue.ROW_2_LEFT
            SeatZone.ROW_2_RIGHT -> SeatValue.ROW_2_RIGHT
        }
        runCatching {
            vehicle.getSeat().getCurrentSeatBeltBuckledState(
                area = SeatArea(sv),
                onComplete = { buckled -> cont.resume(buckled) },
                onFailed = { cont.resume(null) }
            )
        }.onFailure { cont.resume(null) }
    }

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
    }

    override fun unregisterTelemetryCallbacks(listener: VehicleTelemetryListener) {
        listeners.remove(listener)
    }

    override fun release() {
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

    override val runtimeBadgeLabel: String = "[SDK 실연동]"
    override val usesRealPleosSdk: Boolean = true
    override val lowLevelHardware: LowLevelVehicleHardware = PleosLowLevelVehicleAdapter(vehicle)
    override val naviGateway: NaviRouteGateway = pleosNavi
    override val ttsEngine: PleosTtsEngine = pleosTts

    override fun initialize(onInitializedLog: (String) -> Unit) {
        val statusParts = mutableListOf<String>()
        runCatching {
            vehicle.initialize()
            statusParts.add("Vehicle(2.3.1).initialize() 성공")
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
                statusParts.add("FusedLocation(1.1.2) 지원 확인 및 초기화")
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
            val door = vehicle.getDoor()
            door.getCurrentDoorPosition(
                area = DoorArea(DoorValue.ROW_1_LEFT),
                onComplete = { pos: Int? ->
                    onResult(pos, "[Step 5 실호출] door.getCurrentDoorPosition(ROW_1_LEFT) -> onComplete(pos=$pos)")
                },
                onFailed = { e: Exception ->
                    onResult(null, "[Step 5 실호출] door.getCurrentDoorPosition 실패 -> ${e.message}")
                }
            )
        }.onFailure { e ->
            onResult(null, "[Step 5 예외] ${e.message}")
        }
    }

    override fun queryVehicleVin(onResult: (vin: String?, logLine: String) -> Unit) {
        runCatching {
            vehicle.getCarInfo().getVINInfo(
                onComplete = { vin: String? ->
                    onResult(vin, "carInfo.getVINInfo() -> ${vin ?: "null (사용자 설정 차종 폴백)"}")
                },
                onFailed = { e: Exception ->
                    onResult(null, "carInfo.getVINInfo() 폴백 (${e.message})")
                }
            )
        }.onFailure { e ->
            onResult(null, "carInfo.getVINInfo() 미지원 (${e.message})")
        }
    }

    override fun queryLastKnownVehicleLocation(onResult: (VehicleLocationReading) -> Unit) {
        if (!locationInitialized) {
            onResult(
                VehicleLocationReading(
                    supported = false,
                    latitude = null,
                    longitude = null,
                    headingDeg = null,
                    confidence = null,
                    rawSummary = "FusedLocation.isSupported() == false (수동 간판 오프셋 모드)"
                )
            )
            return
        }

        runCatching {
            fusedLocation.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location == null) {
                        onResult(
                            VehicleLocationReading(
                                supported = true,
                                latitude = null,
                                longitude = null,
                                headingDeg = null,
                                confidence = null,
                                rawSummary = "getLastKnownLocation() -> null (저신뢰 텍스트 안내 폴백)"
                            )
                        )
                        return
                    }
                    val lat = location.latitude.toDouble()
                    val lng = location.longitude.toDouble()
                    val heading = location.heading.toDouble()
                    val conf = location.confidence.toDouble()
                    onResult(
                        VehicleLocationReading(
                            supported = true,
                            latitude = lat,
                            longitude = lng,
                            headingDeg = heading,
                            confidence = conf,
                            rawSummary = "FusedLocation(${String.format(Locale.US, "%.5f", lat)}, ${String.format(Locale.US, "%.5f", lng)}, conf=${String.format(Locale.US, "%.2f", conf)})"
                        )
                    )
                }

                override fun onError(exception: Exception) {
                    onResult(
                        VehicleLocationReading(
                            supported = true,
                            latitude = null,
                            longitude = null,
                            headingDeg = null,
                            confidence = null,
                            rawSummary = "FusedLocation 오류 폴백: ${exception.message}"
                        )
                    )
                }
            })
        }.onFailure { e ->
            onResult(
                VehicleLocationReading(
                    supported = false,
                    latitude = null,
                    longitude = null,
                    headingDeg = null,
                    confidence = null,
                    rawSummary = "FusedLocation 호출 예외: ${e.message}"
                )
            )
        }
    }

    override fun queryTurnSignalState(onResult: (PleosTurnSignalState, String) -> Unit) {
        runCatching {
            vehicle.getTurnSignal().getCurrentTurnSignalState(
                onComplete = { state ->
                    val mapped = when (state?.name?.uppercase()) {
                        "RIGHT", "TURN_RIGHT" -> PleosTurnSignalState.RIGHT
                        "LEFT", "TURN_LEFT" -> PleosTurnSignalState.LEFT
                        else -> PleosTurnSignalState.OFF
                    }
                    onResult(mapped, "turnSignal.getCurrentTurnSignalState() -> ${state?.name ?: "OFF"}")
                },
                onFailed = { e ->
                    onResult(PleosTurnSignalState.OFF, "turnSignal 조회 폴백: ${e.message}")
                }
            )
        }.onFailure { e ->
            onResult(PleosTurnSignalState.OFF, "turnSignal 예외: ${e.message}")
        }
    }

    override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit) {
        // 공식 문서(Door.setDoorLock) 극성 준수: value = true(잠금 / LOCK), value = false(잠금 해제 / UNLOCK)
        val lockArgument = !unlocked
        runCatching {
            vehicle.getDoor().setDoorLock(
                area = DoorArea(DoorValue.ROW_1_RIGHT),
                value = lockArgument,
                onComplete = { _, appliedLockState ->
                    val nowUnlocked = (appliedLockState == false)
                    onResult(
                        nowUnlocked,
                        "door.setDoorLock(ROW_1_RIGHT, value=$lockArgument) -> onComplete(locked=$appliedLockState)"
                    )
                },
                onFailed = { e ->
                    onResult(false, "door.setDoorLock 실패 -> 수동 도어 언락 안내 (${e.message})")
                }
            )
        }.onFailure { e ->
            onResult(false, "door.setDoorLock 예외 (${e.message})")
        }
    }

    override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit) {
        val clamped = targetPercent.coerceIn(0, 100)
        runCatching {
            vehicle.getWindow().setWindowPosition(
                area = WindowArea(WindowValue.ROW_1_RIGHT),
                value = clamped,
                onComplete = { _, appliedPos ->
                    val actual = appliedPos ?: clamped
                    onResult(actual, "window.setWindowPosition(ROW_1_RIGHT, $clamped) -> onComplete($actual%)")
                },
                onFailed = { e ->
                    onResult(0, "window.setWindowPosition 실패 (${e.message})")
                }
            )
        }.onFailure { e ->
            onResult(0, "window.setWindowPosition 예외 (${e.message})")
        }
    }

    override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit) {
        val targetSwitch = if (enabled) LightSwitch.ON else LightSwitch.OFF
        runCatching {
            vehicle.getLight().setCabinLightSwitch(
                value = targetSwitch,
                onComplete = { applied ->
                    onResult(applied == LightSwitch.ON, "light.setCabinLightSwitch($targetSwitch) -> onComplete($applied)")
                },
                onFailed = { e ->
                    onResult(false, "실내 조명 제어 미지원 폴백 (${e.message})")
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
                area = SeatArea(seatVal),
                onComplete = { occupied: Boolean? ->
                    onResult(occupied, "seat.getCurrentSeatOccupancy($seatVal) -> onComplete($occupied)")
                },
                onFailed = { e: Exception ->
                    onResult(null, "seat.getCurrentSeatOccupancy 미지원 폴백 (${e.message})")
                }
            )
        }.onFailure { e ->
            onResult(null, "착좌 센서 조회 예외 (${e.message})")
        }
    }

    override fun release() {
        runCatching { pleosTts.release() }
        runCatching { pleosNavi.release() }
        if (locationInitialized) {
            runCatching { fusedLocation.release() }
            locationInitialized = false
        }
        runCatching { vehicle.release() }
    }
}
