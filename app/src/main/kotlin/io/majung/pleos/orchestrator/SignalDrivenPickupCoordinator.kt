package io.majung.pleos.orchestrator

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.SeatZone
import io.majung.pleos.model.VehicleFacts
import io.majung.pleos.safety.DrivingLoadSnapshot
import io.majung.pleos.vehicle.BoardingActuator
import io.majung.pleos.vehicle.PleosCapabilityGateway
import io.majung.pleos.vehicle.VehicleSignalState
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 세션별 운전자 입력(차종·번호판·동승자 프로필). IVI 입력란과 바인딩된다. */
data class PickupInputs(
    val vinCode: String?,
    val fallbackModelName: String,
    val plateLast4: String,
    val passengerProfile: PassengerWelcomeProfile
)

/** 코디네이터가 외부로 남기는 마지막 자동 판단 로그(IVI 인스펙터 바 표시용) */
data class CoordinatorTrace(
    val line: String = "차량 신호 기반 자동 흐름 대기 중",
    val readingLightOn: Boolean = false,
    val windowPercent: Int = 0,
    val driverUnlocked: Boolean = false,
    val approachAnnounced: Boolean = false,
    val greetingSpoken: Boolean = false,
    val cabinNote: String? = null
)

/**
 * 차량 신호(VehicleSignalState)의 변화를 보고 6단계 픽업 흐름을 **자동으로** 전진시키는 코디네이터.
 *
 * 폰 앱이 할 수 없는 세 가지를 차량 신호로만 판단한다:
 *  1) 기어 P + 차속 0 이 되어야 "지금 타도 돼요"(STEP4)를 보낸다.
 *  2) 차도 쪽(뒷좌석 좌측) 문이 열리면 즉시 실내 음성 경고를 낸다.
 *  3) 승차 좌석 문 닫힘 + 안전벨트 체결이 감지되면 공유 세션을 **자동 파기**한다(STEP6).
 *
 * 운전자가 직접 눌러야 하는 것은 단 하나, [주변 확인 완료 · 승차 맞이]다(개문사고 예방을 위해 의도적으로 수동).
 * 그마저도 P단 정차가 아니면 거부된다.
 */
class SignalDrivenPickupCoordinator(
    private val orchestrator: PickupEtaOrchestrator,
    private val capabilityGateway: PleosCapabilityGateway,
    private val finderEngine: CurbsideCarFinderEngine,
    private val welcomeEngine: WelcomeHospitalityEngine,
    private val audioCoordinator: AudioFocusCoordinator,
    private val actuator: BoardingActuator,
    private val signalSourceBadge: String,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile var inputs: PickupInputs = PickupInputs(null, "아이오닉 9", "4568", PassengerWelcomeProfile("동승자", "ADULT", "ROW_1_RIGHT"))

    private val mutex = Mutex()
    private var prev: VehicleSignalState = VehicleSignalState()
    private var lastLocationBroadcastMs = 0L
    private var approachAnnounced = false
    private var stopAnnounced = false
    private var greetingSpoken = false
    private var driverUnlocked = false
    private var readingLightOn = false
    private var windowPercent = 0
    private var lastTickMs = 0L
    private var cabinNote: String? = null

    private val _trace = MutableStateFlow(CoordinatorTrace())
    val trace: StateFlow<CoordinatorTrace> = _trace.asStateFlow()

    fun resetForNewSession() {
        approachAnnounced = false
        stopAnnounced = false
        greetingSpoken = false
        driverUnlocked = false
        readingLightOn = false
        windowPercent = 0
        lastLocationBroadcastMs = 0L
        cabinNote = null
        publishTrace("새 픽업 세션 · 자동 흐름 초기화")
    }

    /** 모든 차량 신호 변화는 이 한 지점으로 들어온다. */
    suspend fun onSignal(state: VehicleSignalState) = mutex.withLock {
        val now = clock()
        val session = orchestrator.uiState.value
        val stop = session.currentStop
        val target = stop?.targetSeat ?: SeatZone.ROW_1_RIGHT

        feedCapabilityGateway(state, now)

        if (now - lastTickMs >= TICK_MIN_INTERVAL_MS || state.gear != prev.gear || state.brakePressed != prev.brakePressed) {
            orchestrator.onVehicleTelemetryTick(
                DrivingLoadSnapshot(
                    speedKmh = state.speedKmh,
                    steeringAngleDeg = state.steeringAngleDeg,
                    steeringRateDegPerSec = state.steeringRateDegPerSec,
                    isBrakePressed = state.brakePressed,
                    isWiperHigh = state.wiperOn,
                    nextTbtDistanceMeters = state.nextTurnDistanceMeters,
                    inSchoolZoneCaution = stop?.rawPinWasInSchoolZone == true
                ),
                state.gear,
                now
            )
            lastTickMs = now
        }

        val distance = distanceToStop(state, stop)
        val facts = VehicleFacts(
            stoppedInPark = state.stoppedInPark,
            trafficSideDoorOpen = state.isDoorOpen(SeatZone.ROW_2_LEFT),
            passengerDoorOpen = state.isDoorOpen(target),
            passengerSeated = state.isSeated(target),
            passengerBuckled = state.isBuckled(target),
            gear = state.gear,
            speedBucketKmh = VehicleFacts.bucketSpeed(state.speedKmh),
            distanceBucketMeters = VehicleFacts.bucketDistance(distance),
            signalSource = signalSourceBadge
        )
        orchestrator.updateVehicleFacts(facts)

        if (session.isSessionActive && stop != null) {
            handleApproachAndStop(state, stop, distance, now)
            handleTrafficSideDoor(state, now)
            handleBoardingSequence(state, target, now)
        }
        prev = state
    }

    /**
     * 운전자가 사이드미러로 주변을 확인한 뒤 누르는 유일한 수동 버튼.
     * P단 정차가 아니면 거부한다(주행 중 도어 언락·창문 개방 방지).
     */
    suspend fun requestSurroundingsCheckedUnlock(latest: VehicleSignalState): Result<String> = mutex.withLock {
        val session = orchestrator.uiState.value
        if (!session.isSessionActive) return@withLock Result.failure(IllegalStateException("진행 중인 픽업 세션이 없습니다."))
        if (!latest.stoppedInPark) {
            val why = "기어 ${latest.gear} · ${"%.0f".format(latest.speedKmh)} km/h — P단 정차 상태에서만 승차 맞이를 시작할 수 있습니다."
            publishTrace(why)
            return@withLock Result.failure(IllegalStateException(why))
        }
        val targetWindow = if (latest.wiperOn) 0 else 25
        // 도어 잠금 해제와 창문 개방은 서로 독립 — 한쪽이 미지원이어도 다른 쪽은 진행한다.
        actuator.setPassengerDoorUnlocked(true) { _, doorLog -> publishTrace(doorLog) }
        actuator.setFrontPassengerWindowPercent(targetWindow) { pct, winLog ->
            windowPercent = pct
            publishTrace(winLog)
        }
        driverUnlocked = true
        orchestrator.notifyBoardingHospitalityState(doorUnlockedInPark = true, welcomeGreetingActive = false)
        val note = if (latest.wiperOn) "우천(와이퍼 작동) 감지 — 창문 개방 생략, 도어 잠금만 해제" else "도어 잠금 해제 · 조수석 창문 25% 개방"
        publishTrace("주변 확인 완료 · $note")
        Result.success(note)
    }

    /** 착좌·벨트 센서가 없는 트림용 수동 종료. 창문·조명을 원복하고 세션을 즉시 파기한다. */
    suspend fun manualCompleteAndShred(): Boolean = mutex.withLock {
        restoreCabinAndShred(manual = true)
    }

    /** 운전자가 IVI에서 [간판 뒤/정면/앞]을 직접 지정 — 위치 오차 구간의 오안내 방어 */
    suspend fun applyManualOffsetPreset(preset: ManualOffsetPreset, latest: VehicleSignalState) = mutex.withLock {
        val stop = orchestrator.uiState.value.currentStop ?: return@withLock
        orchestrator.updateCurbsideStateAndBroadcast(
            vinCode = inputs.vinCode,
            fallbackModelName = inputs.fallbackModelName,
            plateLast4 = inputs.plateLast4,
            actualLat = null, actualLng = null, headingDeg = null, locationConfidence = 0.0,
            distanceMeters = distanceToStop(latest, stop) ?: 0,
            speedKmh = latest.speedKmh,
            turnSignal = latest.turnSignal,
            manualPreset = preset,
            remainingSeconds = 0
        )
        publishTrace("운전자 수동 지정: ${preset.labelKo}")
    }

    // ---- 내부 단계 처리 ---------------------------------------------------------------------

    private fun feedCapabilityGateway(state: VehicleSignalState, now: Long) {
        for ((zone, open) in state.doorOpen) {
            if (prev.doorOpen[zone] != open) capabilityGateway.onDoorEvent(zone, open, now, vehicleStopped = state.stoppedInPark)
        }
        for ((zone, occ) in state.seatOccupied) {
            if (prev.seatOccupied[zone] != occ) capabilityGateway.onSeatOccupancyEvent(zone, occ)
        }
        for ((zone, belt) in state.beltBuckled) {
            if (prev.beltBuckled[zone] != belt) capabilityGateway.onSeatBeltEvent(zone, belt)
        }
    }

    private suspend fun handleApproachAndStop(state: VehicleSignalState, stop: io.majung.pleos.model.SafePitstopStop, distance: Int?, now: Long) {
        val phase = orchestrator.uiState.value.phase
        if (phase.ordinal >= PickupPhase.STEP5_BOARDING_WELCOME.ordinal) return
        val loc = state.location

        // (a) 접근 감지: 반경 80m + 감속 + 우측 방향지시등, 또는 50m 저속 폴백 — 한 번만 알린다.
        if (!approachAnnounced && distance != null) {
            val (triggered, reason) = finderEngine.shouldTriggerApproachNotice(distance, state.speedKmh, state.turnSignal)
            if (triggered) {
                approachAnnounced = true
                broadcastLocation(state, stop, distance, remaining = (distance / 3).coerceAtLeast(5))
                orchestrator.emitPickupPrompt("approach", "'${stop.landmarkSignboard}' 앞 정차 지점에 접근 중입니다. 동승자에게 차량 접근을 알렸습니다.", now)
                // 도착 전 사전 공조: 승차 좌석 풍량·온도 준비, 유아 카시트 좌석 열선은 0으로 고정. 세션 종료 시 원복된다.
                val profile = inputs.passengerProfile
                val outcome = capabilityGateway.applyArrivalPreconditioning(
                    profile = PassengerProfile.entries.firstOrNull { it.name == profile.personaType } ?: PassengerProfile.ADULT,
                    targetZone = stop.targetSeat,
                    hasCarSeat = profile.hasChildCarSeat
                )
                cabinNote = buildString {
                    append(outcome.appliedFanSpeed?.let { "사전 공조 풍량 $it" } ?: "공조 제어 미지원 트림")
                    if (outcome.usedSingleZoneFallback && outcome.appliedFanSpeed != null) append(" (단일 존 폴백)")
                    if (outcome.carSeatHeaterForcedOff) append(" · 카시트 좌석 열선 OFF 고정")
                }
                publishTrace("접근 감지 · $reason · $cabinNote")
                return
            }
        }

        // (b) P단 정차 확정: 기어 P + 차속 0 + 정차 지점 60m 이내 — 이때만 동승자에게 "지금 타도 돼요"
        val justStopped = state.stoppedInPark && !prev.stoppedInPark
        if (!stopAnnounced && state.stoppedInPark && (justStopped || distance != null) && (distance ?: Int.MAX_VALUE) <= STOP_RADIUS_M) {
            stopAnnounced = true
            broadcastLocation(state, stop, distance ?: 0, remaining = 0)
            val reliability = orchestrator.uiState.value.activeTicket?.locationReliability
            val guide = if (reliability == io.majung.pleos.model.LocationReliability.HIGH_CONFIDENCE_AUTO) {
                "P단 정차를 확인했습니다. 간판 기준 정차 위치를 동승자에게 보냈습니다. 주변을 확인한 뒤 승차 맞이를 눌러 주세요."
            } else {
                "P단 정차를 확인했습니다. 위치 오차가 커 간판 앞·정면·뒤 중 하나를 지정해 주세요."
            }
            orchestrator.emitPickupPrompt("stopped", guide, now)
            publishTrace("P단 정차 확정 (${distance ?: "?"}m) · ${loc?.rawSummary ?: "위치 없음"}")
            return
        }

        // (c) 이동 중 주기적 위치 갱신(ETA·거리) — 3초 간격
        if (loc != null && distance != null && now - lastLocationBroadcastMs >= LOCATION_BROADCAST_INTERVAL_MS && !state.stoppedInPark) {
            broadcastLocation(state, stop, distance, remaining = (distance / 3).coerceAtLeast(5))
        }
    }

    private fun handleTrafficSideDoor(state: VehicleSignalState, now: Long) {
        val openNow = state.isDoorOpen(SeatZone.ROW_2_LEFT)
        val openBefore = prev.isDoorOpen(SeatZone.ROW_2_LEFT)
        if (openNow && !openBefore) {
            orchestrator.emitCriticalSafetyPrompt(
                "traffic_side_door",
                "주의하세요. 차도 방향 왼쪽 문이 열렸습니다. 인도 쪽 오른쪽 문을 이용해 주세요.",
                now
            )
            publishTrace("차도측(ROW_2_LEFT) 도어 열림 → 즉시 경고 발화")
        }
    }

    private suspend fun handleBoardingSequence(state: VehicleSignalState, target: SeatZone, now: Long) {
        val phase = orchestrator.uiState.value.phase
        if (phase != PickupPhase.STEP5_BOARDING_WELCOME) return
        val profile = inputs.passengerProfile

        // (d) 승차 좌석 문 열림 → 하향등이 켜진 야간에만 독서등 점등(발밑·버클 시인성). 주간에는 생략.
        if (state.isDoorOpen(target) && !prev.isDoorOpen(target) && !readingLightOn) {
            if (state.lowBeamOn) {
                actuator.setPassengerReadingLight(true) { on, log ->
                    readingLightOn = on
                    publishTrace(log)
                }
            } else {
                publishTrace("승차 문 열림 · 주간(하향등 OFF) → 독서등 생략")
            }
        }

        // (e) 착좌 감지 → "~~님, 반갑습니다." 환대(한 번만)
        if (state.isSeated(target) && !prev.isSeated(target) && !greetingSpoken) {
            greetingSpoken = true
            val directive = welcomeEngine.evaluateState(
                profile = profile,
                isParkGearZeroSpeed = state.stoppedInPark,
                driverCheckedSurroundingsAndUnlocked = driverUnlocked,
                isWiperActiveRain = state.wiperOn,
                exteriorTempCelsius = 14.0,
                isNightLowBeamOn = state.lowBeamOn,
                boardingDoorOpen = state.isDoorOpen(target),
                trafficLeftDoorOpen = state.isDoorOpen(SeatZone.ROW_2_LEFT),
                seatOccupied = true,
                seatBeltBuckledOrManualConfirmed = false
            )
            directive.ttsUtterance?.let { audioCoordinator.speakWithDucking("welcome-$now", it, isCriticalSafety = false) }
            orchestrator.notifyBoardingHospitalityState(doorUnlockedInPark = driverUnlocked, welcomeGreetingActive = true)
            publishTrace("착좌 감지 → 환대 인사: ${directive.welcomeHeadline}")
        }

        // (f) 문 닫힘 + 안전벨트 체결 → 공유 세션 자동 파기(센서 퓨전/시퀀스 추론 모드에서만)
        val readyNow = !state.isDoorOpen(target) && state.isBuckled(target)
        val readyBefore = !prev.isDoorOpen(target) && prev.isBuckled(target)
        if (readyNow && !readyBefore) {
            val done = restoreCabinAndShred(manual = false)
            if (done) {
                val directive = welcomeEngine.evaluateState(
                    profile = profile, isParkGearZeroSpeed = state.stoppedInPark, driverCheckedSurroundingsAndUnlocked = driverUnlocked,
                    isWiperActiveRain = state.wiperOn, exteriorTempCelsius = 14.0, isNightLowBeamOn = state.lowBeamOn,
                    boardingDoorOpen = false, trafficLeftDoorOpen = false, seatOccupied = state.isSeated(target), seatBeltBuckledOrManualConfirmed = true
                )
                directive.ttsUtterance?.let { audioCoordinator.speakWithDucking("depart-$now", it, isCriticalSafety = false) }
                publishTrace("문 닫힘 + 벨트 체결 감지 → 공유 링크 자동 파기")
            } else {
                publishTrace("벨트 체결 감지됐으나 센서 모드(${capabilityGateway.capabilities.value.boardingDetectionMode})상 수동 확인 필요")
            }
        }
    }

    private suspend fun restoreCabinAndShred(manual: Boolean): Boolean {
        val ok = orchestrator.completeAndShredSessionImmediately(manualConfirmed = manual, nowMs = clock())
        if (ok) {
            actuator.setFrontPassengerWindowPercent(0) { pct, _ -> windowPercent = pct }
            actuator.setPassengerReadingLight(false) { on, _ -> readingLightOn = on }
            driverUnlocked = false
            if (cabinNote != null) cabinNote = "공조·열선 세션 전 상태로 원복"
            publishTrace(if (manual) "수동 탑승 확인 → 창문·조명·공조 원복 · 세션 즉시 파기" else "자동 탑승 확인 → 창문·조명·공조 원복 · 세션 즉시 파기")
        }
        return ok
    }

    private suspend fun broadcastLocation(state: VehicleSignalState, stop: io.majung.pleos.model.SafePitstopStop, distance: Int, remaining: Int) {
        val loc = state.location
        lastLocationBroadcastMs = clock()
        orchestrator.updateCurbsideStateAndBroadcast(
            vinCode = inputs.vinCode,
            fallbackModelName = inputs.fallbackModelName,
            plateLast4 = inputs.plateLast4,
            actualLat = loc?.latitude,
            actualLng = loc?.longitude,
            headingDeg = loc?.headingDeg,
            locationConfidence = loc?.confidence,
            distanceMeters = distance,
            speedKmh = state.speedKmh,
            turnSignal = state.turnSignal,
            manualPreset = null,
            remainingSeconds = remaining
        )
    }

    private fun distanceToStop(state: VehicleSignalState, stop: io.majung.pleos.model.SafePitstopStop?): Int? {
        val loc = state.location ?: return null
        val lat = loc.latitude ?: return null
        val lng = loc.longitude ?: return null
        if (stop == null) return null
        return finderEngine.distanceMeters(lat, lng, stop.latitude, stop.longitude)
    }

    private fun publishTrace(line: String) {
        android.util.Log.i("MajungFlow", line) // 에뮬레이터 검증용: adb logcat -s MajungFlow
        _trace.value = CoordinatorTrace(
            line = line,
            readingLightOn = readingLightOn,
            windowPercent = windowPercent,
            driverUnlocked = driverUnlocked,
            approachAnnounced = approachAnnounced,
            greetingSpoken = greetingSpoken,
            cabinNote = cabinNote
        )
    }

    companion object {
        const val STOP_RADIUS_M = 60
        const val LOCATION_BROADCAST_INTERVAL_MS = 3_000L
        const val TICK_MIN_INTERVAL_MS = 500L
    }
}
