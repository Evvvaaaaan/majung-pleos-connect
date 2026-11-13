package io.majung.pleos.orchestrator

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.LocationReliability
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.RecipientChannel
import io.majung.pleos.model.SafePitstopStop
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
        welcomeGreetingActive: Boolean = false
    ): Boolean

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
    val lastBannerMessage: String = ""
)

/**
 * 마중(MAJUNG) v2.0 6단계 픽업 오케스트레이터
 * - 레거시 동물/숫자 패스코드 완전 제거, 차종 + 번호판 끝 4자리 + 조건부 간판 오프셋 기반 동기화
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

        when (firstStop.channel) {
            RecipientChannel.ZERO_INSTALL_WEB ->
                relayGateway.publishSessionState(ticket, firstStop, 180, PickupPhase.STEP1_LANDMARK_AGREED)
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
            lastBannerMessage = schoolNotice
        )
        _uiState.value = state

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

        val nextPhase = when {
            speedKmh <= 1.0f && distanceMeters <= 40 -> PickupPhase.STEP4_CURBSIDE_STOPPED
            eval.approachTriggered -> PickupPhase.STEP2_APPROACHING
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
            phase = nextPhase
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
            welcomeGreetingActive = welcomeGreetingActive
        )
        _uiState.value = current.copy(phase = nextPhase)
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
        _uiState.value = state.copy(dliScore = dli)
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
            lastBannerMessage = "탑승 확인 완료 · 일회성 공유 링크 즉시 파기됨 (미종료 시 최대 20분 자동 만료)"
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
}
