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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import io.majung.pleos.model.SeatZone
import io.majung.pleos.orchestrator.ActiveSessionUiState
import io.majung.pleos.orchestrator.CoordinatorTrace
import io.majung.pleos.orchestrator.PickupEtaOrchestrator
import io.majung.pleos.orchestrator.PickupInputs
import io.majung.pleos.orchestrator.SafePitstopResolver
import io.majung.pleos.orchestrator.SignalDrivenPickupCoordinator
import io.majung.pleos.relay.EphemeralRelayClient
import io.majung.pleos.runtime.MajungRuntimeBridge
import io.majung.pleos.runtime.createMajungRuntimeBridge
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.vehicle.AaosSpeedSource
import io.majung.pleos.vehicle.PleosCapabilityGateway
import io.majung.pleos.vehicle.VehicleSignalHub
import io.majung.pleos.vehicle.VehicleSignalState
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var runtimeBridge: MajungRuntimeBridge
    private lateinit var relayClient: EphemeralRelayClient
    private lateinit var audioCoordinator: AudioFocusCoordinator
    private lateinit var capabilityGateway: PleosCapabilityGateway
    private lateinit var pitstopResolver: SafePitstopResolver
    private lateinit var orchestrator: PickupEtaOrchestrator
    private lateinit var coordinator: SignalDrivenPickupCoordinator
    private val signalHub = VehicleSignalHub()
    private var speedSource: AaosSpeedSource? = null

    private var carInstance: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null
    private var isDrivingRestricted by mutableStateOf(false)

    // 운전자 입력(번호판 끝 4자리는 Pleos SDK가 제공하지 않으므로 직접 입력)
    private var passengerNameInput by mutableStateOf("지수")
    private var destinationKeywordInput by mutableStateOf("파리바게뜨 대치점")
    private var plateLast4Input by mutableStateOf("4568")
    private var fallbackModelInput by mutableStateOf("아이오닉 9")
    private var relayUrlInput by mutableStateOf(BuildConfig.DEFAULT_RELAY_URL)

    private var detectedVin by mutableStateOf<String?>(null)
    private var passengerReadyNote by mutableStateOf<String?>(null)
    private var sdkCallbackLog by mutableStateOf("Pleos Connect 런타임 초기화 대기 중...")
    private var actionNotice by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val finder = CurbsideCarFinderEngine()
        orchestrator = PickupEtaOrchestrator(
            capabilityGateway = capabilityGateway,
            drivingLoadGate = DrivingLoadGate(),
            finderEngine = finder,
            audioCoordinator = audioCoordinator,
            naviGateway = runtimeBridge.naviGateway,
            relayGateway = relayClient,
            relayBaseUrl = relayUrlInput,
            runtimeSourceBadge = runtimeBridge.runtimeBadgeLabel
        )
        coordinator = SignalDrivenPickupCoordinator(
            orchestrator = orchestrator,
            capabilityGateway = capabilityGateway,
            finderEngine = finder,
            welcomeEngine = WelcomeHospitalityEngine(),
            audioCoordinator = audioCoordinator,
            actuator = runtimeBridge,
            signalSourceBadge = runtimeBridge.runtimeBadgeLabel
        )
        syncCoordinatorInputs()

        runtimeBridge.initialize { initSummary -> sdkCallbackLog = initSummary }
        runtimeBridge.queryVehicleVin { vin, vinLog ->
            runOnUiThread {
                detectedVin = vin
                sdkCallbackLog = vinLog
                syncCoordinatorInputs()
            }
        }
        lifecycleScope.launch { capabilityGateway.initializeAndProbe() }

        // --- 차량 신호 실연결: SDK register* → Hub → Coordinator -------------------------------
        runtimeBridge.lowLevelHardware.registerTelemetryCallbacks(signalHub)
        runtimeBridge.startLocationUpdates(signalHub::onLocationFix)
        requestCarSpeedPermissionAndSubscribe()
        lifecycleScope.launch {
            signalHub.state.collect { state -> coordinator.onSignal(state) }
        }

        bindCarUxRestrictions()
        registerGleoBroadcastCommands()
        handleIncomingGleoIntent(intent)

        setContent {
            val session by orchestrator.uiState.collectAsState()
            val signals by signalHub.state.collectAsState()
            val trace by coordinator.trace.collectAsState()
            val isSim = runtimeBridge.supportsSimulatedScenario

            MajungIviScreen(
                runtimeBadge = runtimeBridge.runtimeBadgeLabel,
                isDrivingRestricted = isDrivingRestricted,
                session = session,
                signals = signals,
                trace = trace,
                passengerName = passengerNameInput,
                onPassengerNameChange = { passengerNameInput = it; syncCoordinatorInputs() },
                destinationKeyword = destinationKeywordInput,
                onDestinationKeywordChange = { destinationKeywordInput = it },
                plateLast4 = plateLast4Input,
                onPlateLast4Change = { plateLast4Input = it.take(4); syncCoordinatorInputs() },
                relayUrl = relayUrlInput,
                onRelayUrlChange = { relayUrlInput = it; relayClient.baseUrl = it },
                passengerReadyNote = passengerReadyNote,
                actionNotice = actionNotice,
                sdkCallbackLog = sdkCallbackLog,
                onStartSessionClick = { startSixStepPickupFlow() },
                onSurroundingsCheckedUnlockClick = { requestSurroundingsCheckedUnlock() },
                onManualPresetClick = { preset -> lifecycleScope.launch { coordinator.applyManualOffsetPreset(preset, signalHub.state.value) } },
                onManualCompleteClick = { lifecycleScope.launch { coordinator.manualCompleteAndShred() } },
                showSimControls = isSim,
                onSimDriveClick = { runtimeBridge.playSimulatedApproachAndStop { l -> runOnUiThread { sdkCallbackLog = l } } },
                onSimBoardingClick = { runtimeBridge.playSimulatedPassengerBoarding { l -> runOnUiThread { sdkCallbackLog = l } } },
                onSimWrongDoorClick = { runtimeBridge.playSimulatedTrafficSideDoorMistake { l -> runOnUiThread { sdkCallbackLog = l } } }
            )
        }
    }

    private fun syncCoordinatorInputs() {
        coordinator.inputs = PickupInputs(
            vinCode = detectedVin,
            fallbackModelName = fallbackModelInput,
            plateLast4 = plateLast4Input,
            passengerProfile = PassengerWelcomeProfile(
                displayName = passengerNameInput.ifBlank { "동승자" },
                personaType = "ADULT",
                targetSeatArea = "ROW_1_RIGHT",
                targetTempCelsius = 22.5
            )
        )
    }

    private fun requestCarSpeedPermissionAndSubscribe() {
        if (checkSelfPermission(AaosSpeedSource.PERMISSION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(AaosSpeedSource.PERMISSION), REQ_CAR_SPEED)
            return
        }
        subscribeAaosSpeed()
    }

    private fun subscribeAaosSpeed() {
        speedSource?.stop()
        speedSource = AaosSpeedSource(this).also { src ->
            src.start(onSpeedKmh = signalHub::onSpeedChanged, onLog = { l -> signalHub.onTelemetrySourceLog(l) })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAR_SPEED) subscribeAaosSpeed()
    }

    private fun startSixStepPickupFlow() {
        passengerReadyNote = null
        actionNotice = null
        relayClient.baseUrl = relayUrlInput
        coordinator.resetForNewSession()

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
                            val s = signalHub.state.value
                            orchestrator.updateCurbsideStateAndBroadcast(
                                vinCode = detectedVin,
                                fallbackModelName = fallbackModelInput,
                                plateLast4 = plateLast4Input,
                                actualLat = null, actualLng = null, headingDeg = null, locationConfidence = 0.0,
                                distanceMeters = 999,
                                speedKmh = s.speedKmh,
                                turnSignal = s.turnSignal,
                                manualPreset = null,
                                swappedLandmark = swapped
                            )
                            sdkCallbackLog = "동승자 요청으로 기준 간판 변경됨 -> '$swapped'"
                        }
                    }
                )
            }.onFailure { e ->
                actionNotice = "세션 시작 실패: ${e.message}"
            }
        }
    }

    private fun requestSurroundingsCheckedUnlock() {
        lifecycleScope.launch {
            coordinator.requestSurroundingsCheckedUnlock(signalHub.state.value)
                .onSuccess { actionNotice = null }
                .onFailure { e -> actionNotice = e.message }
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
            cmd.passengerName?.let { passengerNameInput = it; syncCoordinatorInputs() }
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
                        lifecycleScope.launch {
                            coordinator.applyManualOffsetPreset(cmd.manualOffsetPreset ?: ManualOffsetPreset.AHEAD_12M, signalHub.state.value)
                        }
                        "Updated stop offset"
                    }
                    "COMPLETE_OR_CANCEL_PICKUP" -> {
                        lifecycleScope.launch { coordinator.manualCompleteAndShred() }
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
        speedSource?.stop()
        runtimeBridge.stopLocationUpdates()
        runtimeBridge.lowLevelHardware.unregisterTelemetryCallbacks(signalHub)
        relayClient.close()
        audioCoordinator.release()
        runtimeBridge.release()
        runCatching {
            uxRestrictionsManager?.unregisterListener()
            carInstance?.disconnect()
        }
        super.onDestroy()
    }

    companion object {
        private const val REQ_CAR_SPEED = 0x5EED
    }
}

// =====================================================================================================
// IVI 화면 (Pleos ccNC 다크 언어 · 17" 가로 기준, 12.9"에서도 세로 스크롤로 동작)
// =====================================================================================================

private val T = MajungTokens

@Composable
fun MajungIviScreen(
    runtimeBadge: String,
    isDrivingRestricted: Boolean,
    session: ActiveSessionUiState,
    signals: VehicleSignalState,
    trace: CoordinatorTrace,
    passengerName: String,
    onPassengerNameChange: (String) -> Unit,
    destinationKeyword: String,
    onDestinationKeywordChange: (String) -> Unit,
    plateLast4: String,
    onPlateLast4Change: (String) -> Unit,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    passengerReadyNote: String?,
    actionNotice: String?,
    sdkCallbackLog: String,
    onStartSessionClick: () -> Unit,
    onSurroundingsCheckedUnlockClick: () -> Unit,
    onManualPresetClick: (ManualOffsetPreset) -> Unit,
    onManualCompleteClick: () -> Unit,
    showSimControls: Boolean,
    onSimDriveClick: () -> Unit,
    onSimBoardingClick: () -> Unit,
    onSimWrongDoorClick: () -> Unit
) {
    val facts = session.vehicleFacts
    val target = session.currentStop?.targetSeat ?: SeatZone.ROW_1_RIGHT
    val trafficDoorOpen = signals.isDoorOpen(SeatZone.ROW_2_LEFT)

    val (headline, subline, heroTone) = when {
        trafficDoorOpen -> Triple("차도 쪽 문이 열렸습니다", "후방 이륜차·차량 접근 위험. 인도 쪽 오른쪽 문을 이용해 주세요.", T.Danger)
        session.phase == PickupPhase.IDLE -> Triple("${passengerName.ifBlank { "동승자" }}님 마중 준비", "기준 간판을 정하고 일회성 안심 링크를 보내면 시작됩니다.", T.HyundaiBlue)
        session.phase == PickupPhase.STEP1_LANDMARK_AGREED -> Triple("'${session.activeTicket?.landmarkSignboard}'로 이동 중", "접근하면 동승자에게 자동으로 알립니다.", T.HyundaiBlue)
        session.phase == PickupPhase.STEP2_APPROACHING -> Triple("정차 지점 접근 중", session.lastBannerMessage, T.ActiveBlue)
        session.phase == PickupPhase.STEP4_CURBSIDE_STOPPED && !signals.stoppedInPark -> Triple("정차 후 P단으로 바꿔 주세요", "P단이 확인되어야 동승자에게 '지금 타도 돼요'가 전달됩니다.", T.Warning)
        session.phase == PickupPhase.STEP4_CURBSIDE_STOPPED -> Triple("P단 정차 확인", "사이드미러로 후방을 확인한 뒤 승차 맞이를 눌러 주세요.", T.ActiveBlue)
        session.phase == PickupPhase.STEP5_BOARDING_WELCOME && signals.isSeated(target) -> Triple("${passengerName.ifBlank { "동승자" }}님, 반갑습니다.", "문 닫힘과 안전벨트가 확인되면 공유가 자동으로 종료됩니다.", T.Success)
        session.phase == PickupPhase.STEP5_BOARDING_WELCOME -> Triple("승차 맞이 중", "조수석 잠금 해제 · 창문 ${trace.windowPercent}% · 동승자가 타는 것을 기다립니다.", T.ActiveBlue)
        else -> Triple("탑승 완료 · 공유 종료", session.lastBannerMessage, T.Success)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = T.Canvas) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 상단 바: 앱명 · 출처 배지 · 단계 스테퍼
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("마중", color = T.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("MAJUNG", color = T.Muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Chip(runtimeBadge, if (runtimeBadge.contains("SDK")) T.ActiveBlue else T.Muted)
                    if (isDrivingRestricted) Chip("주행 중 · 입력 제한", T.Warning)
                }
                PhaseStepper(session.phase)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // 좌: 히어로 상태 카드
                Column(
                    modifier = Modifier
                        .weight(1.35f)
                        .background(if (heroTone == T.Success && session.phase == PickupPhase.STEP5_BOARDING_WELCOME) T.Sand else T.Surface, RoundedCornerShape(28.dp))
                        .border(1.dp, T.Hairline, RoundedCornerShape(28.dp))
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val onSand = heroTone == T.Success && session.phase == PickupPhase.STEP5_BOARDING_WELCOME
                    Box(modifier = Modifier.size(width = 56.dp, height = 6.dp).background(heroTone, CircleShape))
                    Text(headline, color = if (onSand) T.SandInk else T.Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
                    Text(subline, color = if (onSand) T.SandInk.copy(alpha = 0.7f) else T.Muted, fontSize = 18.sp, lineHeight = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Chip("번호판 끝 ${plateLast4.ifBlank { "----" }}", T.Ink, filled = true)
                        Chip(session.activeTicket?.vehicleModelLabel ?: "차종 확인 중", T.Muted)
                        session.activeTicket?.let { Chip("기준 간판 · ${it.landmarkSignboard}", T.Muted) }
                    }
                    if (passengerReadyNote != null) {
                        Text("동승자: $passengerReadyNote", color = T.ActiveBlue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (actionNotice != null) {
                        Text(actionNotice, color = T.Warning, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }

                // 우: 차량 신호 패널 — "차만 아는 사실"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(T.Surface, RoundedCornerShape(28.dp))
                        .border(1.dp, T.Hairline, RoundedCornerShape(28.dp))
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("차량 신호", color = T.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BigStat("기어", signals.gear.name, if (signals.gear.name == "P") T.Success else T.Ink)
                        BigStat("차속", "${signals.speedKmh.toInt()}", T.Ink, unit = "km/h")
                        BigStat("거리", facts.distanceBucketMeters?.let { "$it" } ?: "—", T.Ink, unit = "m")
                    }
                    SignalRow("P단 정차", signals.stoppedInPark, okText = "확인", pendingText = "대기")
                    SignalRow("방향지시등", signals.turnSignal.name != "OFF", okText = signals.turnSignal.name, pendingText = "OFF", neutral = true)
                    SignalRow("조수석 문", signals.isDoorOpen(target), okText = "열림", pendingText = "닫힘", neutral = true)
                    SignalRow("차도쪽 문(2열 좌)", trafficDoorOpen, okText = "열림 · 경고", pendingText = "닫힘", danger = true)
                    SignalRow("착좌", signals.isSeated(target), okText = "감지", pendingText = "비어 있음")
                    SignalRow("안전벨트", signals.isBuckled(target), okText = "체결", pendingText = "미체결")
                    Text("속도 출처 ${signals.speedSource.name.lowercase()} · 이벤트 ${signals.eventCount}건 · ${signals.lastEventLog}", color = T.Muted, fontSize = 12.sp)
                }
            }

            // 입력 행 (주행 제한 시 숨김)
            if (!isDrivingRestricted) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DarkField(passengerName, onPassengerNameChange, "동승자 호칭", Modifier.weight(0.8f))
                    DarkField(destinationKeyword, onDestinationKeywordChange, "픽업 장소·간판", Modifier.weight(1.3f))
                    DarkField(plateLast4, onPlateLast4Change, "번호판 끝 4자리", Modifier.weight(0.7f))
                    DarkField(relayUrl, onRelayUrlChange, "동승자 웹 중계 서버", Modifier.weight(1.3f))
                }
            }

            // 액션 행: 자동 흐름에서 운전자가 누르는 것은 두 개뿐
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("1 · 픽업 시작 · 링크 발급", enabled = !session.isSessionActive, onClick = onStartSessionClick, modifier = Modifier.weight(1f))
                PrimaryButton(
                    "주변 확인 완료 · 승차 맞이",
                    enabled = session.isSessionActive && signals.stoppedInPark && !trace.driverUnlocked,
                    onClick = onSurroundingsCheckedUnlockClick,
                    modifier = Modifier.weight(1f),
                    tone = T.ActiveBlue
                )
                SecondaryButton("탑승 확인 · 종료 (센서 미지원 시)", enabled = session.isSessionActive, onClick = onManualCompleteClick, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("위치 오차 시 직접 지정", color = T.Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
                ManualOffsetPreset.entries.forEach { p ->
                    SecondaryButton(p.labelKo, enabled = session.isSessionActive && signals.stoppedInPark, onClick = { onManualPresetClick(p) }, modifier = Modifier.weight(1f))
                }
            }

            if (showSimControls) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("시뮬레이션", color = T.Warning, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterVertically))
                    SecondaryButton("주행 → 간판 앞 P단 정차", enabled = session.isSessionActive, onClick = onSimDriveClick, modifier = Modifier.weight(1f))
                    SecondaryButton("동승자 탑승(문·착좌·벨트)", enabled = session.phase == PickupPhase.STEP5_BOARDING_WELCOME, onClick = onSimBoardingClick, modifier = Modifier.weight(1f))
                    SecondaryButton("차도 쪽 문 잘못 열림", enabled = session.isSessionActive, onClick = onSimWrongDoorClick, modifier = Modifier.weight(1f))
                }
            }

            // 인스펙터: SDK 콜백 / 자동 판단 로그
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(T.SurfaceRaised, RoundedCornerShape(18.dp))
                    .border(1.dp, T.Hairline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("자동 판단  ${trace.line}", color = T.Ink, fontSize = 13.sp)
                Text("SDK/중계  $sdkCallbackLog", color = T.Muted, fontSize = 12.sp)
                Text("공유 링크  ${session.activeTicket?.shareUrl ?: "세션 시작 시 생성 (최대 20분, 탑승 확인 즉시 파기)"}", color = T.Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PhaseStepper(phase: PickupPhase) {
    val labels = listOf("합의", "접근", "정차", "맞이", "종료")
    val idx = when (phase) {
        PickupPhase.IDLE -> -1
        PickupPhase.STEP1_LANDMARK_AGREED -> 0
        PickupPhase.STEP2_APPROACHING, PickupPhase.STEP3_VEHICLE_IDENTIFIED -> 1
        PickupPhase.STEP4_CURBSIDE_STOPPED -> 2
        PickupPhase.STEP5_BOARDING_WELCOME -> 3
        PickupPhase.STEP6_COMPLETED_SHREDDED -> 4
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            val active = i <= idx
            Box(
                modifier = Modifier
                    .background(if (active) T.ActiveBlue else T.Surface, CircleShape)
                    .border(1.dp, if (active) T.ActiveBlue else T.Hairline, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("${i + 1} $l", color = if (active) T.Canvas else T.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Chip(text: String, tone: Color, filled: Boolean = false) {
    Box(
        modifier = Modifier
            .background(if (filled) tone else Color.Transparent, CircleShape)
            .border(1.dp, if (filled) tone else T.Hairline, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text, color = if (filled) T.Canvas else tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BigStat(label: String, value: String, tone: Color, unit: String? = null) {
    Column(
        modifier = Modifier
            .background(T.SurfaceRaised, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = T.Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, color = tone, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (unit != null) Text(unit, color = T.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        }
    }
}

@Composable
private fun SignalRow(label: String, on: Boolean, okText: String, pendingText: String, neutral: Boolean = false, danger: Boolean = false) {
    val tone = when {
        on && danger -> T.Danger
        on && neutral -> T.ActiveBlue
        on -> T.Success
        else -> T.Muted
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = T.Ink, fontSize = 15.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(10.dp).background(tone, CircleShape))
            Text(if (on) okText else pendingText, color = tone, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DarkField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = T.Muted) },
        singleLine = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = T.Ink, unfocusedTextColor = T.Ink,
            focusedBorderColor = T.ActiveBlue, unfocusedBorderColor = T.Hairline,
            cursorColor = T.ActiveBlue
        )
    )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tone: Color = T.Ink) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = tone, contentColor = T.Canvas, disabledContainerColor = T.SurfaceRaised, disabledContentColor = T.Muted),
        modifier = modifier.heightIn(min = 60.dp)
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SecondaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Ink, disabledContentColor = T.Muted),
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
