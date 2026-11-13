package io.majung.pleos.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.majung.pleos.BuildConfig
import io.majung.pleos.audio.AndroidVehicleAudioPlatform
import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.gleo.GleoActionReceiver
import io.majung.pleos.gleo.GleoCommandBus
import io.majung.pleos.gleo.GleoProtocolCodec
import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.orchestrator.PickupEtaOrchestrator
import io.majung.pleos.orchestrator.SafePitstopResolver
import io.majung.pleos.relay.EphemeralRelayClient
import io.majung.pleos.runtime.MajungRuntimeBridge
import io.majung.pleos.runtime.createMajungRuntimeBridge
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.vehicle.PleosCapabilityGateway
import io.majung.pleos.welcome.BoardingHospitalityStage
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import kotlinx.coroutines.launch

// Mobbin Monochrome Gallery Design System Tokens (무그림자 · 헤어라인 · 스타디움 필)
private val MobbinCanvas = Color(0xFFFFFFFF)
private val MobbinCanvasSoft = Color(0xFFF3F3F3)
private val MobbinSurfaceHover = Color(0xFFF0F0F0)
private val MobbinHairline = Color(0xFFE0E0E0)
private val MobbinInk = Color(0xFF141414)
private val MobbinMuted = Color(0xFF707070)
private val MobbinAccentBlue = Color(0xFF0066FF)

class MainActivity : ComponentActivity() {

    private lateinit var runtimeBridge: MajungRuntimeBridge
    private lateinit var relayClient: EphemeralRelayClient
    private lateinit var audioCoordinator: AudioFocusCoordinator
    private lateinit var capabilityGateway: PleosCapabilityGateway
    private lateinit var pitstopResolver: SafePitstopResolver
    private lateinit var orchestrator: PickupEtaOrchestrator

    private var carInstance: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null
    private var isDrivingRestricted by mutableStateOf(false)

    private val welcomeEngine = WelcomeHospitalityEngine()

    // 사용자 입력 가능 차량·동승자 설정 (번호판 끝 4자리는 Pleos SDK 비제공이므로 운전자 직접 입력값 사용)
    private var passengerNameInput by mutableStateOf("지수")
    private var destinationKeywordInput by mutableStateOf("파리바게뜨 대치점")
    private var plateLast4Input by mutableStateOf("4568")
    private var fallbackModelInput by mutableStateOf("아이오닉 9")
    private var relayUrlInput by mutableStateOf(BuildConfig.DEFAULT_RELAY_URL)

    private var detectedVin by mutableStateOf<String?>(null)
    private var step5DoorLeftPos by mutableStateOf<Int?>(null)
    private var passengerReadyNote by mutableStateOf<String?>(null)
    private var lowConfidenceModeForced by mutableStateOf(false)

    private var isParkGear by mutableStateOf(true)
    private var driverCheckedAndUnlocked by mutableStateOf(false)
    private var doorOpen by mutableStateOf(false)
    private var seatOccupied by mutableStateOf(false)
    private var beltOrManualConfirmed by mutableStateOf(false)
    private var appliedWindowPct by mutableStateOf(0)
    private var appliedReadingLightOn by mutableStateOf(false)

    private var sdkCallbackLog by mutableStateOf("Pleos Connect 런타임 초기화 대기 중...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) 실제 Pleos SDK (pleos flavor) 또는 시뮬레이션 (sim flavor) 브리지 생성 및 초기화
        runtimeBridge = createMajungRuntimeBridge(this)
        relayClient = EphemeralRelayClient(baseUrl = relayUrlInput)
        audioCoordinator = AudioFocusCoordinator(
            audioPlatform = AndroidVehicleAudioPlatform(this),
            ttsEngine = runtimeBridge.ttsEngine
        )
        audioCoordinator.initialize()

        capabilityGateway = PleosCapabilityGateway(runtimeBridge.lowLevelHardware)
        val pitstopsJson = assets.open("safe_pitstops_kr.json").bufferedReader().use { it.readText() }
        pitstopResolver = SafePitstopResolver(pitstopsJson)

        orchestrator = PickupEtaOrchestrator(
            capabilityGateway = capabilityGateway,
            drivingLoadGate = DrivingLoadGate(),
            finderEngine = CurbsideCarFinderEngine(),
            audioCoordinator = audioCoordinator,
            naviGateway = runtimeBridge.naviGateway,
            relayGateway = relayClient,
            relayBaseUrl = relayUrlInput,
            runtimeSourceBadge = runtimeBridge.runtimeBadgeLabel
        )

        runtimeBridge.initialize { initSummary ->
            sdkCallbackLog = initSummary
        }

        // 2) 공식 가이드(getting-started Step 5) 첫 번째 API 실호출: DoorPosition(ROW_1_LEFT) & VIN 조회
        executeGettingStartedStep5Probe()
        lifecycleScope.launch {
            capabilityGateway.initializeAndProbe()
        }

        bindCarUxRestrictions()
        registerGleoBroadcastCommands()
        handleIncomingGleoIntent(intent)

        setContent {
            val sessionState by orchestrator.uiState.collectAsState()
            val profile = PassengerWelcomeProfile(
                displayName = passengerNameInput.ifBlank { "동승자" },
                personaType = "ADULT",
                targetSeatArea = "ROW_1_RIGHT",
                targetTempCelsius = 22.5
            )
            val directive = welcomeEngine.evaluateState(
                profile = profile,
                isParkGearZeroSpeed = isParkGear,
                driverCheckedSurroundingsAndUnlocked = driverCheckedAndUnlocked,
                isWiperActiveRain = false,
                exteriorTempCelsius = 14.0,
                isNightLowBeamOn = true,
                boardingDoorOpen = doorOpen,
                trafficLeftDoorOpen = (step5DoorLeftPos ?: 0) > 0,
                seatOccupied = seatOccupied,
                seatBeltBuckledOrManualConfirmed = beltOrManualConfirmed
            )

            MajungImplementableIviScreen(
                runtimeBadge = runtimeBridge.runtimeBadgeLabel,
                isDrivingRestricted = isDrivingRestricted,
                passengerName = passengerNameInput,
                onPassengerNameChange = { passengerNameInput = it },
                destinationKeyword = destinationKeywordInput,
                onDestinationKeywordChange = { destinationKeywordInput = it },
                plateLast4 = plateLast4Input,
                onPlateLast4Change = { plateLast4Input = it.take(4) },
                relayUrl = relayUrlInput,
                onRelayUrlChange = {
                    relayUrlInput = it
                    relayClient.baseUrl = it
                },
                sessionPhase = sessionState.phase,
                shareUrl = sessionState.activeTicket?.shareUrl ?: "픽업 시작 시 일회성 공유 링크(최대 20분, 종료 즉시 파기)가 생성됩니다.",
                agreedLandmark = sessionState.activeTicket?.landmarkSignboard ?: "미정 (1단계 픽업 시작 버튼을 눌러 합의)",
                offsetGuideText = sessionState.activeTicket?.offsetGuideText ?: "정차 전 대기 중",
                passengerReadyNote = passengerReadyNote,
                step5DoorLeftPos = step5DoorLeftPos,
                welcomeStage = directive.stage,
                welcomeHeadline = directive.welcomeHeadline ?: "${profile.displayName}님 마중 준비",
                welcomeSubtitle = directive.welcomeSubtitle
                    ?: "합의한 기준 간판과 차종·번호판 끝 4자리(${plateLast4Input})로 동승자와 어긋남 없이 만납니다.",
                windowPercent = appliedWindowPct,
                readingLightOn = appliedReadingLightOn,
                lowConfidenceForced = lowConfidenceModeForced,
                sdkCallbackLog = sdkCallbackLog,
                onReRunStep5Click = { executeGettingStartedStep5Probe() },
                onStartSessionClick = { startSixStepPickupFlow() },
                onAutoLocationStopClick = {
                    lowConfidenceModeForced = false
                    evaluateStopOffset(manualPreset = null)
                },
                onToggleLowConfidenceClick = {
                    lowConfidenceModeForced = !lowConfidenceModeForced
                    evaluateStopOffset(manualPreset = null)
                },
                onManualPresetClick = { preset -> evaluateStopOffset(manualPreset = preset) },
                onSurroundingsCheckedUnlockClick = { triggerSurroundingsCheckedBoardingWelcome() },
                onPassengerSeatedGreetingClick = { triggerPassengerSeatedGreeting(profile) },
                onCompleteAndShredClick = { completeBoardingAndShredImmediately() }
            )
        }
    }

    private fun executeGettingStartedStep5Probe() {
        runtimeBridge.queryStep5FrontLeftDoorPosition { pos, logLine ->
            runOnUiThread {
                step5DoorLeftPos = pos
                sdkCallbackLog = logLine
            }
        }
        runtimeBridge.queryVehicleVin { vin, vinLog ->
            runOnUiThread {
                detectedVin = vin
                sdkCallbackLog = "$sdkCallbackLog | $vinLog"
            }
        }
    }

    private fun startSixStepPickupFlow() {
        driverCheckedAndUnlocked = false
        doorOpen = false
        seatOccupied = false
        beltOrManualConfirmed = false
        passengerReadyNote = null
        relayClient.baseUrl = relayUrlInput

        val stop = pitstopResolver.resolveKeywordToSafePitstop(
            keyword = destinationKeywordInput,
            passengerName = passengerNameInput,
            profile = PassengerProfile.ADULT
        )
        lifecycleScope.launch {
            orchestrator.startPickupSession(
                stops = listOf(stop),
                vinCode = detectedVin,
                fallbackModelName = fallbackModelInput,
                plateLast4 = plateLast4Input
            ).onSuccess { state ->
                sdkCallbackLog = "1단계 완료: NaviHelper 경로 요청 + SSE 일회성 세션(${state.sessionId}) 발급"
                relayClient.subscribePassengerEvents(
                    sessionId = state.sessionId,
                    onPassengerReady = { note ->
                        runOnUiThread {
                            passengerReadyNote = note
                            sdkCallbackLog = "동승자 웹 수신: '$note'"
                        }
                    },
                    onLandmarkSwapRequested = { swapped ->
                        lifecycleScope.launch {
                            orchestrator.updateCurbsideStateAndBroadcast(
                                vinCode = detectedVin,
                                fallbackModelName = fallbackModelInput,
                                plateLast4 = plateLast4Input,
                                actualLat = null,
                                actualLng = null,
                                headingDeg = null,
                                locationConfidence = 0.0,
                                distanceMeters = 60,
                                speedKmh = 15f,
                                turnSignal = io.majung.pleos.finder.PleosTurnSignalState.OFF,
                                manualPreset = null,
                                swappedLandmark = swapped
                            )
                            sdkCallbackLog = "동승자 요청으로 기준 간판 변경됨 -> '$swapped'"
                        }
                    }
                )
            }
        }
    }

    private fun evaluateStopOffset(manualPreset: ManualOffsetPreset?) {
        runtimeBridge.queryTurnSignalState { turnState, turnLog ->
            runtimeBridge.queryLastKnownVehicleLocation { loc ->
                val effectiveConf = if (lowConfidenceModeForced) 0.25 else loc.confidence
                lifecycleScope.launch {
                    val updated = orchestrator.updateCurbsideStateAndBroadcast(
                        vinCode = detectedVin,
                        fallbackModelName = fallbackModelInput,
                        plateLast4 = plateLast4Input,
                        actualLat = if (lowConfidenceModeForced) null else loc.latitude,
                        actualLng = if (lowConfidenceModeForced) null else loc.longitude,
                        headingDeg = loc.headingDeg,
                        locationConfidence = effectiveConf,
                        distanceMeters = 18,
                        speedKmh = 0f,
                        turnSignal = turnState,
                        manualPreset = manualPreset,
                        remainingSeconds = 0
                    )
                    runOnUiThread {
                        isParkGear = true
                        sdkCallbackLog = "${loc.rawSummary} | $turnLog | 안내: ${updated?.locationReliability ?: "N/A"}"
                    }
                }
            }
        }
    }

    private fun triggerSurroundingsCheckedBoardingWelcome() {
        isParkGear = true
        driverCheckedAndUnlocked = true
        runtimeBridge.setPassengerDoorUnlocked(unlocked = true) { _, doorLog ->
            runtimeBridge.setFrontPassengerWindowPercent(targetPercent = 25) { pct, winLog ->
                runOnUiThread {
                    appliedWindowPct = pct
                    sdkCallbackLog = "$doorLog | $winLog"
                }
                lifecycleScope.launch {
                    orchestrator.notifyBoardingHospitalityState(
                        doorUnlockedInPark = true,
                        welcomeGreetingActive = false
                    )
                }
            }
        }
    }

    private fun triggerPassengerSeatedGreeting(profile: PassengerWelcomeProfile) {
        doorOpen = true
        runtimeBridge.setPassengerReadingLight(enabled = true) { lightOn, lightLog ->
            runtimeBridge.queryPassengerSeatOccupancy(useRearRight = false) { occupied, seatLog ->
                runOnUiThread {
                    appliedReadingLightOn = lightOn
                    seatOccupied = occupied ?: true
                    val directive = welcomeEngine.evaluateState(
                        profile = profile,
                        isParkGearZeroSpeed = true,
                        driverCheckedSurroundingsAndUnlocked = true,
                        isWiperActiveRain = false,
                        exteriorTempCelsius = 14.0,
                        isNightLowBeamOn = true,
                        boardingDoorOpen = true,
                        trafficLeftDoorOpen = false,
                        seatOccupied = true,
                        seatBeltBuckledOrManualConfirmed = false
                    )
                    directive.ttsUtterance?.let { greeting ->
                        audioCoordinator.speakWithDucking(
                            utteranceId = "welcome-${System.currentTimeMillis()}",
                            text = greeting,
                            isCriticalSafety = false
                        )
                    }
                    sdkCallbackLog = "$lightLog | $seatLog | Pleos TTS 발화: "${profile.displayName}님, 반갑습니다.""
                }
                lifecycleScope.launch {
                    orchestrator.notifyBoardingHospitalityState(
                        doorUnlockedInPark = true,
                        welcomeGreetingActive = true
                    )
                }
            }
        }
    }

    private fun completeBoardingAndShredImmediately() {
        doorOpen = false
        beltOrManualConfirmed = true
        runtimeBridge.setFrontPassengerWindowPercent(targetPercent = 0) { pct, winLog ->
            runtimeBridge.setPassengerReadingLight(enabled = false) { lightOn, lightLog ->
                runOnUiThread {
                    appliedWindowPct = pct
                    appliedReadingLightOn = lightOn
                }
                lifecycleScope.launch {
                    orchestrator.completeAndShredSessionImmediately(manualConfirmed = true)
                    runOnUiThread {
                        sdkCallbackLog = "$winLog | $lightLog | DELETE /api/v1/sessions -> 토큰 즉시 파기 완료"
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingGleoIntent(intent)
    }

    private fun handleIncomingGleoIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != "START_SAFE_PICKUP") return
        val rawParam = intent.getStringExtra(GleoProtocolCodec.EXTRA_ACTION_PARAM)
        GleoProtocolCodec.parseActionIntent(action, rawParam).onSuccess { cmd ->
            cmd.passengerName?.let { passengerNameInput = it }
            cmd.destinationKeyword?.let { destinationKeywordInput = it }
            startSixStepPickupFlow()
            GleoActionReceiver.sendGleoBroadcast(
                context = this,
                functionName = "START_SAFE_PICKUP",
                status = "success",
                message = "Started Majung pickup session for $destinationKeywordInput"
            )
        }
    }

    private fun registerGleoBroadcastCommands() {
        GleoCommandBus.onServiceCommandReceived = { _, cmd ->
            runCatching {
                when (cmd.functionName) {
                    "SHARE_PICKUP_OFFSET" -> {
                        evaluateStopOffset(cmd.manualOffsetPreset ?: ManualOffsetPreset.AHEAD_12M)
                        "Updated stop offset"
                    }
                    "COMPLETE_OR_CANCEL_PICKUP" -> {
                        completeBoardingAndShredImmediately()
                        "Completed pickup and shredded session token"
                    }
                    else -> "Handled ${cmd.functionName}"
                }
            }
        }
    }

    private fun bindCarUxRestrictions() {
        runCatching {
            carInstance = Car.createCar(this)
            uxRestrictionsManager =
                carInstance?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            isDrivingRestricted =
                uxRestrictionsManager?.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            uxRestrictionsManager?.registerListener { restrictions ->
                isDrivingRestricted = restrictions.isRequiresDistractionOptimization
            }
        }
    }

    override fun onDestroy() {
        GleoCommandBus.onServiceCommandReceived = null
        relayClient.close()
        audioCoordinator.release()
        runtimeBridge.release()
        runCatching {
            uxRestrictionsManager?.unregisterListener()
            carInstance?.disconnect()
        }
        super.onDestroy()
    }
}

@Composable
fun MajungImplementableIviScreen(
    runtimeBadge: String,
    isDrivingRestricted: Boolean,
    passengerName: String,
    onPassengerNameChange: (String) -> Unit,
    destinationKeyword: String,
    onDestinationKeywordChange: (String) -> Unit,
    plateLast4: String,
    onPlateLast4Change: (String) -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    sessionPhase: PickupPhase,
    shareUrl: String,
    agreedLandmark: String,
    offsetGuideText: String,
    passengerReadyNote: String?,
    step5DoorLeftPos: Int?,
    welcomeStage: BoardingHospitalityStage,
    welcomeHeadline: String,
    welcomeSubtitle: String,
    windowPercent: Int,
    readingLightOn: Boolean,
    lowConfidenceForced: Boolean,
    sdkCallbackLog: String,
    onReRunStep5Click: () -> Unit,
    onStartSessionClick: () -> Unit,
    onAutoLocationStopClick: () -> Unit,
    onToggleLowConfidenceClick: () -> Unit,
    onManualPresetClick: (ManualOffsetPreset) -> Unit,
    onSurroundingsCheckedUnlockClick: () -> Unit,
    onPassengerSeatedGreetingClick: () -> Unit,
    onCompleteAndShredClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MobbinCanvas),
        color = MobbinCanvas
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 상단 플로팅 스타디움 헤더 (실제 SDK / 시뮬레이션 출처 배지 상시 표기)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MobbinCanvasSoft, CircleShape)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "마중 Majung",
                        color = MobbinInk,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .background(MobbinCanvas, CircleShape)
                            .border(1.dp, MobbinHairline, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = runtimeBadge,
                            color = MobbinInk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Step 5 Door(ROW_1_LEFT): ${step5DoorLeftPos ?: 0}",
                        color = MobbinMuted,
                        fontSize = 13.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .background(
                            if (welcomeStage == BoardingHospitalityStage.SEATED_WELCOME_GREETING) MobbinAccentBlue else MobbinInk,
                            CircleShape
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "단계: ${sessionPhase.name}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 2. 중앙 마중 환대 및 합의 간판·차량 정보 카드 (24dp 라운드, 무그림자)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MobbinCanvasSoft, RoundedCornerShape(24.dp))
                    .border(1.dp, MobbinHairline, RoundedCornerShape(24.dp))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = welcomeHeadline,
                    color = MobbinInk,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = welcomeSubtitle,
                    color = MobbinMuted,
                    fontSize = 16.sp
                )
                Text(
                    text = "기준 간판: $agreedLandmark · 정차 안내: $offsetGuideText",
                    color = MobbinInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "일회성 안심 웹: $shareUrl",
                    color = MobbinMuted,
                    fontSize = 13.sp
                )
                if (passengerReadyNote != null) {
                    Text(
                        text = "동승자 실시간 응답: $passengerReadyNote",
                        color = MobbinAccentBlue,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    InfoPill("번호판 끝 4자리: $plateLast4")
                    InfoPill("조수석 창문: ${windowPercent}%")
                    InfoPill(if (readingLightOn) "승차 조명: ON" else "승차 조명: OFF")
                    InfoPill(if (lowConfidenceForced) "위치 신뢰도: 저신뢰 폴백" else "위치 신뢰도: 자동/수동")
                }
            }

            // 3. 주행 제한(Distraction Optimization) 미적용 정차 시 설정 입력 행
            if (!isDrivingRestricted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = passengerName,
                        onValueChange = onPassengerNameChange,
                        label = { Text("동승자 호칭") },
                        singleLine = true,
                        modifier = Modifier.weight(0.9f)
                    )
                    OutlinedTextField(
                        value = destinationKeyword,
                        onValueChange = onDestinationKeywordChange,
                        label = { Text("픽업 장소·간판 키워드") },
                        singleLine = true,
                        modifier = Modifier.weight(1.3f)
                    )
                    OutlinedTextField(
                        value = plateLast4,
                        onValueChange = onPlateLast4Change,
                        label = { Text("번호판 4자리") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                    OutlinedTextField(
                        value = relayUrl,
                        onValueChange = onRelayUrlChange,
                        label = { Text("SSE 중계 서버 URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f)
                    )
                }
            }

            // 4. 6단계 핵심 시나리오 실행 버튼 행 (최소 높이 48dp 스타디움 필)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionPillButton(
                    label = "Step 5 도어·VIN 재조회",
                    filled = false,
                    onClick = onReRunStep5Click
                )
                ActionPillButton(
                    label = "1. 픽업 시작 · 링크 발급",
                    filled = true,
                    onClick = onStartSessionClick
                )
                ActionPillButton(
                    label = "2~4. FusedLocation 정차 판정",
                    filled = false,
                    onClick = onAutoLocationStopClick
                )
                ActionPillButton(
                    label = if (lowConfidenceForced) "지하·저신뢰 폴백 해제" else "지하·저신뢰 폴백 시험",
                    filled = false,
                    onClick = onToggleLowConfidenceClick
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ActionPillButton(
                    label = "수동: 간판 뒤(-10m)",
                    filled = false,
                    onClick = { onManualPresetClick(ManualOffsetPreset.BEHIND_10M) }
                )
                ActionPillButton(
                    label = "수동: 간판 정면(0m)",
                    filled = false,
                    onClick = { onManualPresetClick(ManualOffsetPreset.FRONT_0M) }
                )
                ActionPillButton(
                    label = "수동: 간판 앞(+12m)",
                    filled = false,
                    onClick = { onManualPresetClick(ManualOffsetPreset.AHEAD_12M) }
                )
                ActionPillButton(
                    label = "5-A. 주변확인 완료 · 승차맞이",
                    filled = true,
                    onClick = onSurroundingsCheckedUnlockClick
                )
                ActionPillButton(
                    label = "5-B. ${passengerName}님 착좌 환대",
                    filled = true,
                    onClick = onPassengerSeatedGreetingClick
                )
                ActionPillButton(
                    label = "6. 탑승확인 · 공유 즉시종료",
                    filled = false,
                    onClick = onCompleteAndShredClick
                )
            }

            // 5. 실시간 Pleos SDK IPC & 콜백 인스펙터 바
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MobbinSurfaceHover, RoundedCornerShape(16.dp))
                    .border(1.dp, MobbinHairline, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "실시간 SDK / 중계 로그: $sdkCallbackLog",
                    color = MobbinInk,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun InfoPill(text: String) {
    Box(
        modifier = Modifier
            .background(MobbinCanvas, CircleShape)
            .border(1.dp, MobbinHairline, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = MobbinInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActionPillButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit
) {
    if (filled) {
        Button(
            onClick = onClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MobbinInk),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            shape = CircleShape,
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text(text = label, color = MobbinInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
