package io.majung.pleos.orchestrator

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.finder.bearingDeg
import io.majung.pleos.finder.offsetPoint
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.CannotStopOption
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.GeoPoint
import io.majung.pleos.model.LiveMapState
import io.majung.pleos.model.LocationReliability
import io.majung.pleos.model.PassengerSuggestion
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.RecipientChannel
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SuggestionKind
import io.majung.pleos.model.VehicleFacts
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.safety.DrivingLoadSnapshot
import io.majung.pleos.safety.GatedPrompt
import io.majung.pleos.safety.PromptPriority
import io.majung.pleos.vehicle.BoardingEvaluation
import io.majung.pleos.vehicle.PleosCapabilityGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class NaviRouteSlice(
    val firstWaypoint: SafePitstopStop?,
    val secondWaypoint: SafePitstopStop?,
    val finalDestination: SafePitstopStop
)

interface NaviRouteGateway {
    suspend fun dispatchRouteWithWaypoints(slice: NaviRouteSlice): Boolean
    suspend fun cancelActiveRoute(): Boolean
}

interface RelayNotificationGateway {
    suspend fun publishSessionState(
        ticket: PickupSessionShareTicket,
        stop: SafePitstopStop,
        remainingSeconds: Int,
        phase: PickupPhase,
        boardingDoorUnlocked: Boolean = false,
        welcomeGreetingActive: Boolean = false,
        facts: VehicleFacts = VehicleFacts(),
        liveMap: LiveMapState = LiveMapState()
    ): Boolean

    /** 동승자 제안에 대한 운전자 응답을 중계 서버에 전달한다. */
    suspend fun replySuggestion(sessionId: String, suggestionId: String, accepted: Boolean): Boolean

    suspend fun sendKidsFallbackSms(stop: SafePitstopStop, smsBody: String): Boolean
    suspend fun shredEphemeralSession(sessionId: String): Boolean
}

data class ActiveSessionUiState(
    val sessionId: String = "",
    val phase: PickupPhase = PickupPhase.IDLE,
    val currentStop: SafePitstopStop? = null,
    val remainingStopsQueue: List<SafePitstopStop> = emptyList(),
    val activeTicket: PickupSessionShareTicket? = null,
    val remainingEtaSeconds: Int = 180,
    val boardingEvaluation: BoardingEvaluation? = null,
    val dliScore: Float = 0f,
    val lastBannerMessage: String = "",
    val boardingDoorUnlocked: Boolean = false,
    val welcomeGreetingActive: Boolean = false,
    val vehicleFacts: VehicleFacts = VehicleFacts(),
    val liveMap: LiveMapState = LiveMapState(),
    val pendingSuggestion: PassengerSuggestion? = null
) {
    val isSessionActive: Boolean
        get() = phase != PickupPhase.IDLE && phase != PickupPhase.STEP6_COMPLETED_SHREDDED
}

/**
 * 마중(MAJUNG) v2.1 6단계 픽업 오케스트레이터
 * - 차종 + 번호판 끝 4자리 + 조건부 간판 오프셋 기반 동기화
 * - 차량 신호(P단·도어·착좌·벨트)를 VehicleFacts로 동승자 웹에 그대로 전달
 * - 탑승 완료 또는 수동 종료 시 즉시 토큰 파기(DELETE) + 미종료 시 최대 20분 자동 만료(TTL)
 */
class PickupEtaOrchestrator(
    private val capabilityGateway: PleosCapabilityGateway,
    private val drivingLoadGate: DrivingLoadGate,
    private val finderEngine: CurbsideCarFinderEngine,
    private val audioCoordinator: AudioFocusCoordinator,
    private val naviGateway: NaviRouteGateway,
    private val relayGateway: RelayNotificationGateway,
    private val relayBaseUrl: String,
    private val runtimeSourceBadge: String = "[SDK 실연동]"
) {
    private val _uiState = MutableStateFlow(ActiveSessionUiState())
    val uiState: StateFlow<ActiveSessionUiState> = _uiState.asStateFlow()

    suspend fun startPickupSession(
        stops: List<SafePitstopStop>,
        vinCode: String?,
        fallbackModelName: String,
        plateLast4: String,
        nowMs: Long = System.currentTimeMillis()
    ): Result<ActiveSessionUiState> = runCatching {
        require(stops.isNotEmpty()) { "최소 1개 이상의 픽업 후보 지점이 필요합니다." }

        val sessionId = "mj-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val oneTimeToken = UUID.randomUUID().toString().replace("-", "").take(16)
        val firstStop = stops.first()
        val tailStops = stops.drop(1)

        val modelLabel = finderEngine.resolveModelFromVin(vinCode, fallbackModelName)
        val shareUrl = "${relayBaseUrl.trimEnd('/')}/m/$sessionId?t=$oneTimeToken"

        val ticket = PickupSessionShareTicket(
            sessionId = sessionId,
            oneTimeToken = oneTimeToken,
            shareUrl = shareUrl,
            vehicleModelLabel = modelLabel,
            plateLast4 = plateLast4.takeLast(4).ifBlank { "4568" },
            landmarkSignboard = firstStop.landmarkSignboard,
            candidateSignboards = firstStop.candidateSignboards,
            offsetMeters = null,
            offsetGuideText = "합의된 기준 간판: '${firstStop.landmarkSignboard}' · 차량 접근 시 정차 위치가 업데이트됩니다.",
            locationReliability = LocationReliability.LOW_CONFIDENCE_FALLBACK,
            runtimeSourceBadge = runtimeSourceBadge,
            issuedAtEpochMs = nowMs
        )

        dispatchSlidingNaviWindow(listOf(firstStop) + tailStops)

        val facts = _uiState.value.vehicleFacts.copy(signalSource = runtimeSourceBadge)
        val liveMap = LiveMapState(
            car = _uiState.value.liveMap.car,
            carHeadingDeg = _uiState.value.liveMap.carHeadingDeg,
            carUpdatedAtMs = _uiState.value.liveMap.carUpdatedAtMs,
            pickup = GeoPoint(firstStop.latitude, firstStop.longitude),
            pickupLabel = firstStop.landmarkSignboard,
            pickupVersion = 1
        )
        when (firstStop.channel) {
            RecipientChannel.ZERO_INSTALL_WEB ->
                relayGateway.publishSessionState(ticket, firstStop, 180, PickupPhase.STEP1_LANDMARK_AGREED, facts = facts, liveMap = liveMap)
            RecipientChannel.KIDS_SMS ->
                relayGateway.sendKidsFallbackSms(firstStop, ticket.buildCompactFallbackSms(3))
        }

        val schoolNotice = if (firstStop.rawPinWasInSchoolZone) {
            "어린이보호구역 주정차 제한을 고려해 인근 '${firstStop.landmarkSignboard}' 후보 포켓으로 경로를 설정했습니다."
        } else {
            "'${firstStop.landmarkSignboard}' 기준 픽업 세션을 시작했습니다."
        }

        val state = ActiveSessionUiState(
            sessionId = sessionId,
            phase = PickupPhase.STEP1_LANDMARK_AGREED,
            currentStop = firstStop,
            remainingStopsQueue = tailStops,
            activeTicket = ticket,
            remainingEtaSeconds = 180,
            lastBannerMessage = schoolNotice,
            vehicleFacts = facts,
            liveMap = liveMap
        )
        _uiState.value = state
        lastCarPublishMs = nowMs
        lastPublishedPickupVersion = 1

        deliverThroughGate(
            GatedPrompt(
                id = "start-$sessionId",
                categoryKey = "session_lifecycle",
                spokenText = "$schoolNotice 동승자 안심 웹 링크가 준비되었습니다.",
                bannerTitle = schoolNotice,
                priority = PromptPriority.HIGH_PICKUP_EVENT,
                createdAtMs = nowMs
            ),
            nowMs
        )
        state
    }

    suspend fun updateCurbsideStateAndBroadcast(
        vinCode: String?,
        fallbackModelName: String,
        plateLast4: String,
        actualLat: Double?,
        actualLng: Double?,
        headingDeg: Double?,
        locationConfidence: Double?,
        distanceMeters: Int,
        speedKmh: Float,
        turnSignal: PleosTurnSignalState,
        manualPreset: ManualOffsetPreset?,
        swappedLandmark: String? = null,
        remainingSeconds: Int = 35
    ): PickupSessionShareTicket? {
        val current = _uiState.value
        val stop = current.currentStop ?: return null
        val oldTicket = current.activeTicket ?: return null

        val effectiveStop = if (!swappedLandmark.isNullOrBlank()) {
            stop.copy(landmarkSignboard = swappedLandmark)
        } else {
            stop
        }

        val eval = finderEngine.evaluateCurbsideStop(
            vinCode = vinCode,
            fallbackModelName = fallbackModelName,
            plateLast4 = plateLast4,
            landmarkName = effectiveStop.landmarkSignboard,
            spotLat = effectiveStop.latitude,
            spotLng = effectiveStop.longitude,
            actualLat = actualLat,
            actualLng = actualLng,
            headingDeg = headingDeg,
            locationConfidence = locationConfidence,
            distanceMeters = distanceMeters,
            speedKmh = speedKmh,
            turnSignal = turnSignal,
            manualPreset = manualPreset
        )

        // 5·6단계에 들어간 뒤에는 위치 갱신으로 단계가 뒤로 돌아가지 않는다.
        // 4단계(정차)는 차속 0만으로는 부족하다 — 기어 P가 확인된 VehicleFacts.stoppedInPark 일 때만 넘어간다.
        val nextPhase = when {
            current.phase.ordinal >= PickupPhase.STEP5_BOARDING_WELCOME.ordinal -> current.phase
            manualPreset != null -> PickupPhase.STEP4_CURBSIDE_STOPPED
            current.vehicleFacts.stoppedInPark && distanceMeters <= 60 -> PickupPhase.STEP4_CURBSIDE_STOPPED
            eval.approachTriggered -> maxPhase(current.phase, PickupPhase.STEP2_APPROACHING)
            else -> current.phase
        }

        val updatedTicket = oldTicket.copy(
            vehicleModelLabel = eval.vehicleModelLabel,
            plateLast4 = eval.licensePlateLast4,
            landmarkSignboard = effectiveStop.landmarkSignboard,
            offsetMeters = eval.offsetMeters,
            offsetGuideText = eval.passengerGuideText,
            locationReliability = eval.reliability
        )

        relayGateway.publishSessionState(
            ticket = updatedTicket,
            stop = effectiveStop,
            remainingSeconds = remainingSeconds,
            phase = nextPhase,
            boardingDoorUnlocked = current.boardingDoorUnlocked,
            welcomeGreetingActive = current.welcomeGreetingActive,
            facts = current.vehicleFacts,
            liveMap = current.liveMap
        )

        _uiState.value = current.copy(
            phase = nextPhase,
            currentStop = effectiveStop,
            activeTicket = updatedTicket,
            remainingEtaSeconds = remainingSeconds,
            lastBannerMessage = eval.passengerGuideText
        )
        return updatedTicket
    }

    suspend fun notifyBoardingHospitalityState(
        doorUnlockedInPark: Boolean,
        welcomeGreetingActive: Boolean
    ) {
        val current = _uiState.value
        val stop = current.currentStop ?: return
        val ticket = current.activeTicket ?: return
        val nextPhase = PickupPhase.STEP5_BOARDING_WELCOME
        relayGateway.publishSessionState(
            ticket = ticket,
            stop = stop,
            remainingSeconds = 0,
            phase = nextPhase,
            boardingDoorUnlocked = doorUnlockedInPark,
            welcomeGreetingActive = welcomeGreetingActive,
            facts = current.vehicleFacts,
            liveMap = current.liveMap
        )
        _uiState.value = current.copy(
            phase = nextPhase,
            boardingDoorUnlocked = doorUnlockedInPark,
            welcomeGreetingActive = welcomeGreetingActive
        )
    }

    /**
     * 차량 신호 요약(VehicleFacts)이 의미 있게 바뀌었을 때만 동승자 웹에 재전송한다.
     * 세션이 없으면 상태만 보관한다(다음 세션 시작 시 첫 publish에 실린다).
     */
    suspend fun updateVehicleFacts(facts: VehicleFacts): Boolean {
        val current = _uiState.value
        if (current.vehicleFacts == facts) return false
        _uiState.value = current.copy(vehicleFacts = facts)
        val stop = current.currentStop
        val ticket = current.activeTicket
        if (!current.isSessionActive || stop == null || ticket == null) return false
        relayGateway.publishSessionState(
            ticket = ticket,
            stop = stop,
            remainingSeconds = current.remainingEtaSeconds,
            phase = current.phase,
            boardingDoorUnlocked = current.boardingDoorUnlocked,
            welcomeGreetingActive = current.welcomeGreetingActive,
            facts = facts,
            liveMap = current.liveMap
        )
        return true
    }

    private var lastCarPublishMs: Long? = null
    private var lastPublishedPickupVersion = 1

    /** 차량 위치 갱신. 지도 데이터만 바꾸며 단계 전이는 건드리지 않는다. 2초마다(픽업 지점이 바뀌면 즉시) 동승자 웹으로 전송. */
    suspend fun updateCarLocation(point: GeoPoint, headingDeg: Double?, nowMs: Long) {
        val current = _uiState.value
        val map = current.liveMap.copy(car = point, carHeadingDeg = headingDeg ?: current.liveMap.carHeadingDeg, carUpdatedAtMs = nowMs)
        _uiState.value = current.copy(liveMap = map)
        if (!current.isSessionActive) return
        if (lastCarPublishMs.let { it == null || nowMs - it >= CAR_PUBLISH_INTERVAL_MS } || map.pickupVersion != lastPublishedPickupVersion) {
            publishCurrent(nowMs)
        }
    }

    /** 동승자 위치는 동승자 폰이 이미 알고 있으므로 지도 상태만 갱신하고 다시 보내지 않는다. */
    fun onPassengerLocation(point: GeoPoint, accuracyM: Double?, nowMs: Long) {
        val current = _uiState.value
        _uiState.value = current.copy(
            liveMap = current.liveMap.copy(passenger = point, passengerAccuracyM = accuracyM, passengerUpdatedAtMs = nowMs)
        )
    }

    fun onPassengerSuggestion(s: PassengerSuggestion) {
        val stop = _uiState.value.currentStop ?: return
        _uiState.value = _uiState.value.copy(pendingSuggestion = s)
        emitPickupPrompt("suggestion", "${stop.passengerName}님 제안: ${s.text}", s.atMs)
        if (s.kind == SuggestionKind.READY) {
            _uiState.value = _uiState.value.copy(lastBannerMessage = "${stop.passengerName}님이 준비 완료를 알렸어요")
        }
    }

    /** 운전자가 동승자 제안을 수락/거절. 응답은 항상 동승자에게 전달하고 대기 제안을 비운다. */
    suspend fun resolvePendingSuggestion(accept: Boolean) {
        val s = _uiState.value.pendingSuggestion ?: return
        if (accept) {
            when {
                s.kind == SuggestionKind.PICKUP_HERE && s.point != null ->
                    movePickup(s.point, "동승자 위치", "동승자 위치에서 태웁니다")
                s.kind == SuggestionKind.OTHER_SIDE -> applyCannotStopOption(CannotStopOption.OTHER_SIDE_UTURN, s.atMs)
                else -> _uiState.value = _uiState.value.copy(
                    liveMap = _uiState.value.liveMap.copy(pickupNote = "동승자: ${s.text}".take(80))
                )
            }
        }
        _uiState.value = _uiState.value.copy(pendingSuggestion = null)
        val sessionId = _uiState.value.sessionId
        relayGateway.replySuggestion(sessionId, s.id, accept)
        publishCurrent()
    }

    /** "여기 못 세워요" 대안 적용. 진행 중인 세션이 없으면 false. */
    suspend fun applyCannotStopOption(option: CannotStopOption, nowMs: Long): Boolean {
        val current = _uiState.value
        val pickup = current.liveMap.pickup
        if (!current.isSessionActive || current.currentStop == null || pickup == null) return false
        val heading = current.liveMap.carHeadingDeg
            ?: current.liveMap.car?.let { bearingDeg(it, pickup) }
            ?: 0.0
        when (option) {
            CannotStopOption.AHEAD_30M ->
                movePickup(offsetPoint(pickup, heading, 30.0), option.labelKo, "앞으로 30 m 이동해서 태울게요")
            CannotStopOption.OTHER_SIDE_UTURN ->
                // 우측통행: 건너편 갓길은 진행 방향의 왼쪽(heading - 90°)
                movePickup(offsetPoint(pickup, heading - 90.0, 14.0), option.labelKo, "건너편에서 태울게요 (U턴)")
            CannotStopOption.LOOP_AROUND ->
                _uiState.value = current.copy(liveMap = current.liveMap.copy(pickupNote = "한 바퀴 돌고 다시 올게요 (약 3분)"))
        }
        publishCurrent(nowMs)
        emitPickupPrompt(
            "cannot_stop",
            when (option) {
                CannotStopOption.AHEAD_30M -> "앞으로 30미터 더 가서 세우겠습니다."
                CannotStopOption.OTHER_SIDE_UTURN -> "건너편으로 U턴해서 세우겠습니다."
                CannotStopOption.LOOP_AROUND -> "한 바퀴 돌고 다시 오겠습니다."
            },
            nowMs
        )
        return true
    }

    /** 픽업 지점을 옮기고 버전을 올리며 내비 경로를 다시 요청한다. 발행은 호출자가 한다. */
    private suspend fun movePickup(point: GeoPoint, label: String, note: String) {
        val current = _uiState.value
        val stop = current.currentStop ?: return
        val moved = stop.copy(latitude = point.lat, longitude = point.lng)
        _uiState.value = current.copy(
            currentStop = moved,
            liveMap = current.liveMap.copy(
                pickup = point, pickupLabel = label, pickupNote = note, pickupVersion = current.liveMap.pickupVersion + 1
            )
        )
        naviGateway.dispatchRouteWithWaypoints(buildNaviRouteSlice(listOf(moved)))
    }

    private suspend fun publishCurrent(nowMs: Long? = null) {
        val current = _uiState.value
        val stop = current.currentStop ?: return
        val ticket = current.activeTicket ?: return
        if (nowMs != null) lastCarPublishMs = nowMs
        lastPublishedPickupVersion = current.liveMap.pickupVersion
        relayGateway.publishSessionState(
            ticket = ticket,
            stop = stop,
            remainingSeconds = current.remainingEtaSeconds,
            phase = current.phase,
            boardingDoorUnlocked = current.boardingDoorUnlocked,
            welcomeGreetingActive = current.welcomeGreetingActive,
            facts = current.vehicleFacts,
            liveMap = current.liveMap
        )
    }

    /** 차도 쪽 문 열림처럼 즉시 발화해야 하는 안전 경고. DLI 게이트를 우회(CRITICAL)한다. */
    fun emitCriticalSafetyPrompt(key: String, spokenText: String, nowMs: Long = System.currentTimeMillis()) {
        deliverThroughGate(
            GatedPrompt(
                id = "$key-$nowMs",
                categoryKey = key,
                spokenText = spokenText,
                bannerTitle = spokenText,
                priority = PromptPriority.CRITICAL_INTERLOCK,
                createdAtMs = nowMs
            ),
            nowMs
        )
    }

    /** 운전 부하가 낮을 때만 전달되는 일반 픽업 안내(접근·정차 확인 등). */
    fun emitPickupPrompt(key: String, spokenText: String, nowMs: Long = System.currentTimeMillis()) {
        deliverThroughGate(
            GatedPrompt(
                id = "$key-$nowMs",
                categoryKey = key,
                spokenText = spokenText,
                bannerTitle = spokenText,
                priority = PromptPriority.HIGH_PICKUP_EVENT,
                createdAtMs = nowMs
            ),
            nowMs
        )
    }

    fun onVehicleTelemetryTick(
        snapshot: DrivingLoadSnapshot,
        gear: GearPosition,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val state = _uiState.value
        val dli = drivingLoadGate.updateTelemetry(snapshot, nowMs)
        drivingLoadGate.drainEligiblePrompts(nowMs).forEach { emitPromptImmediately(it) }

        val stop = state.currentStop
        if (stop != null && state.phase == PickupPhase.STEP5_BOARDING_WELCOME) {
            capabilityGateway.checkDepartureHazard(stop.targetSeat, gear)?.let { hazard ->
                deliverThroughGate(
                    GatedPrompt(
                        id = "hazard-$nowMs",
                        categoryKey = "critical_departure_hazard",
                        spokenText = hazard,
                        bannerTitle = hazard,
                        priority = PromptPriority.CRITICAL_INTERLOCK,
                        createdAtMs = nowMs
                    ),
                    nowMs
                )
            }
        }
        _uiState.value = _uiState.value.copy(dliScore = dli)
    }

    /**
     * 6단계: 탑승 완료 확인(센서 시퀀스 또는 수동 버튼) -> 즉시 세션 토큰 파기(DELETE) 및 액추에이터 원복
     */
    suspend fun completeAndShredSessionImmediately(
        manualConfirmed: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val state = _uiState.value
        val stop = state.currentStop
        val eval = stop?.let { capabilityGateway.evaluateBoardingStatus(it.targetSeat, nowMs) }
        if (!manualConfirmed && eval?.isReadyToDepartSafely != true) {
            _uiState.value = state.copy(boardingEvaluation = eval)
            return false
        }

        capabilityGateway.activateFreshAirSmoothMode()
        if (state.sessionId.isNotEmpty()) {
            relayGateway.shredEphemeralSession(state.sessionId)
        }
        naviGateway.cancelActiveRoute()
        capabilityGateway.rollbackSessionStateAndRelease(releaseSdk = false)

        _uiState.value = state.copy(
            phase = PickupPhase.STEP6_COMPLETED_SHREDDED,
            boardingEvaluation = eval,
            remainingEtaSeconds = 0,
            boardingDoorUnlocked = false,
            welcomeGreetingActive = false,
            lastBannerMessage = if (manualConfirmed) {
                "탑승 확인(수동) 완료 · 일회성 공유 링크 즉시 파기됨"
            } else {
                "문 닫힘·안전벨트 체결 감지 · 일회성 공유 링크 자동 파기됨 (미종료 시 최대 20분 자동 만료)"
            }
        )
        return true
    }

    fun buildNaviRouteSlice(orderedStops: List<SafePitstopStop>): NaviRouteSlice {
        require(orderedStops.isNotEmpty())
        val window = orderedStops.take(3)
        return when (window.size) {
            1 -> NaviRouteSlice(null, null, window[0])
            2 -> NaviRouteSlice(window[0], null, window[1])
            else -> NaviRouteSlice(window[0], window[1], window[2])
        }
    }

    private suspend fun dispatchSlidingNaviWindow(orderedStops: List<SafePitstopStop>) {
        naviGateway.dispatchRouteWithWaypoints(buildNaviRouteSlice(orderedStops))
    }

    private fun maxPhase(a: PickupPhase, b: PickupPhase): PickupPhase = if (a.ordinal >= b.ordinal) a else b

    private fun deliverThroughGate(prompt: GatedPrompt, nowMs: Long) {
        drivingLoadGate.submitOrHold(prompt, nowMs).forEach { emitPromptImmediately(it) }
    }

    private fun emitPromptImmediately(prompt: GatedPrompt) {
        _uiState.value = _uiState.value.copy(lastBannerMessage = prompt.bannerTitle)
        audioCoordinator.speakWithDucking(
            utteranceId = prompt.id,
            text = prompt.spokenText,
            isCriticalSafety = (prompt.priority == PromptPriority.CRITICAL_INTERLOCK)
        )
    }

    companion object {
        const val CAR_PUBLISH_INTERVAL_MS = 2_000L
    }
}
