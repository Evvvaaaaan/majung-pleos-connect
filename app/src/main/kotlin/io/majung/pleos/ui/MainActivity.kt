package io.majung.pleos.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.lifecycleScope
import io.majung.pleos.BuildConfig
import io.majung.pleos.audio.AndroidVehicleAudioPlatform
import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.gleo.GleoActionReceiver
import io.majung.pleos.gleo.GleoCommandBus
import io.majung.pleos.gleo.GleoProtocolCodec
import io.majung.pleos.model.GeoPoint
import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.PassengerSuggestion
import io.majung.pleos.model.SuggestionKind
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
    private lateinit var suggestionNotifier: SuggestionNotifier

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

        runtimeBridge.initialize { initSummary ->
            sdkCallbackLog = initSummary
            android.util.Log.i("MajungSDK", "init: $initSummary")
        }
        runtimeBridge.queryVehicleVin { vin, vinLog ->
            runOnUiThread {
                detectedVin = vin
                sdkCallbackLog = vinLog
                syncCoordinatorInputs()
            }
        }
        lifecycleScope.launch { capabilityGateway.initializeAndProbe() }

        // 내비가 앞에 있어도 동승자 제안에 답할 수 있게 헤드업 알림으로도 띄운다
        suggestionNotifier = SuggestionNotifier(this) { accept -> lifecycleScope.launch { coordinator.resolveSuggestion(accept) } }
        suggestionNotifier.register()
        lifecycleScope.launch {
            var shownId: String? = null
            orchestrator.uiState.collect { st ->
                val s = st.pendingSuggestion
                if (s?.id != shownId) {
                    shownId = s?.id
                    val pickup = st.liveMap.pickup; val spot = s?.point
                    val dist = if (pickup != null && spot != null) finder.distanceMeters(pickup.lat, pickup.lng, spot.lat, spot.lng) else null
                    suggestionNotifier.show(s, st.currentStop?.passengerName.orEmpty(), dist)
                }
            }
        }

        // --- 차량 신호 실연결: SDK register* → Hub → Coordinator -------------------------------
        runtimeBridge.lowLevelHardware.registerTelemetryCallbacks(signalHub)
        requestCarSpeedPermissionAndSubscribe()
        lifecycleScope.launch {
            signalHub.state.collect { state -> coordinator.onSignal(state) }
        }
        // FusedLocation 서비스 바인딩은 비동기라 initialize() 직후 등록은 유실될 수 있다(Pleos 에뮬레이터에서 확인).
        // 3초 뒤 연속 콜백을 등록하고, 세션 중에는 2초마다 getLastKnownLocation 으로 보강한다.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(3_000)
            runtimeBridge.startLocationUpdates(signalHub::onLocationFix)
            while (true) {
                kotlinx.coroutines.delay(2_000)
                if (orchestrator.uiState.value.isSessionActive) {
                    runtimeBridge.queryLastKnownVehicleLocation { r -> if (r.latitude != null && r.longitude != null) signalHub.onLocationFix(r) }
                }
            }
        }

        bindCarUxRestrictions()
        registerGleoBroadcastCommands()
        registerDebugLocationHook()
        handleIncomingGleoIntent(intent)

        setContent {
            val session by orchestrator.uiState.collectAsState()
            val signals by signalHub.state.collectAsState()
            val trace by coordinator.trace.collectAsState()
            val isSim = runtimeBridge.supportsSimulatedScenario

            // 화면은 Pleos Connect(2560×1440 @160dpi = 2560dp 폭) 기준으로 그리고, 더 좁은 IVI에서는 비례 축소한다.
            val configuration = LocalConfiguration.current
            val baseDensity = LocalDensity.current
            val k = (configuration.screenWidthDp / 2560f).coerceIn(0.55f, 1f)
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density * k, baseDensity.fontScale)) {
                MajungIviScreen(
                    runtimeBadge = runtimeBridge.runtimeBadgeLabel,
                    isDrivingRestricted = isDrivingRestricted,
                    session = session,
                    signals = signals,
                    trace = trace,
                    inputs = IviInputs(passengerNameInput, destinationKeywordInput, plateLast4Input, relayUrlInput),
                    onInputsChange = { i ->
                        passengerNameInput = i.passengerName
                        destinationKeywordInput = i.destinationKeyword
                        plateLast4Input = i.plateLast4.take(4)
                        if (i.relayUrl != relayUrlInput) { relayUrlInput = i.relayUrl; relayClient.baseUrl = i.relayUrl }
                        syncCoordinatorInputs()
                    },
                    passengerReadyNote = passengerReadyNote,
                    actionNotice = actionNotice,
                    sdkCallbackLog = sdkCallbackLog,
                    onStartSession = { startSixStepPickupFlow() },
                    onSurroundingsCheckedUnlock = { requestSurroundingsCheckedUnlock() },
                    onCannotStop = { option -> lifecycleScope.launch { coordinator.cannotStopHere(option) } },
                    onResolveSuggestion = { accept -> lifecycleScope.launch { coordinator.resolveSuggestion(accept) } },
                    onEndSession = { lifecycleScope.launch { coordinator.manualCompleteAndShred() } },
                    simControls = if (isSim) SimControls(
                        onDrive = { runtimeBridge.playSimulatedApproachAndStop { l -> runOnUiThread { sdkCallbackLog = l } } },
                        onBoarding = { runtimeBridge.playSimulatedPassengerBoarding { l -> runOnUiThread { sdkCallbackLog = l } } },
                        onWrongDoor = { runtimeBridge.playSimulatedTrafficSideDoorMistake { l -> runOnUiThread { sdkCallbackLog = l } } },
                        onPassengerSuggest = {
                            // 승차 지점에서 동쪽으로 약 30 m: 중계 서버가 보내는 것과 같은 두 호출을 그대로 탄다
                            orchestrator.uiState.value.liveMap.pickup?.let { p ->
                                val now = System.currentTimeMillis()
                                val spot = GeoPoint(p.lat, p.lng + 30.0 / 88_300.0)
                                orchestrator.onPassengerLocation(spot, 12.0, now)
                                orchestrator.onPassengerSuggestion(PassengerSuggestion("sim-$now", SuggestionKind.PICKUP_HERE, "여기서 탈게요", spot, null, now))
                            }
                        }
                    ) else null
                )
            }
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
        // 차속 권한과 알림 권한(API 33+)을 한 번에 묻는다. 권한 요청은 동시에 하나만 처리되기 때문이다.
        val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
        val missing = buildList {
            if (checkSelfPermission(AaosSpeedSource.PERMISSION) != granted) add(AaosSpeedSource.PERMISSION)
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != granted) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_CAR_SPEED)
        if (AaosSpeedSource.PERMISSION !in missing) subscribeAaosSpeed()
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
        if (requestCode == REQ_CAR_SPEED && AaosSpeedSource.PERMISSION in permissions) subscribeAaosSpeed()
    }

    private fun startSixStepPickupFlow() {
        passengerReadyNote = null
        actionNotice = null
        relayClient.baseUrl = relayUrlInput
        runtimeBridge.resetSimulatedScenario()
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
                    },
                    onPassengerLocation = { point, accuracyM ->
                        runOnUiThread { orchestrator.onPassengerLocation(point, accuracyM, System.currentTimeMillis()) }
                    },
                    onPassengerSuggestion = { suggestion ->
                        runOnUiThread { orchestrator.onPassengerSuggestion(suggestion) }
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

    // ---- 디버그 빌드 전용: Pleos 에뮬레이터에는 FusedLocation 주입 수단이 없어, 시연·검증용으로 좌표를 adb로 넣는다.
    //   adb shell am broadcast -a io.majung.pleos.DEBUG_LOCATION --ef lat 37.4972 --ef lng 127.0598 --ef heading 90
    // release 빌드에는 등록되지 않는다. 실차·실 에뮬레이터 위치가 들어오면 그 값이 그대로 우선한다(같은 허브로 합쳐짐).
    private var debugLocationReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugLocationHook() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val lat = intent?.getFloatExtra("lat", Float.NaN) ?: return
                val lng = intent.getFloatExtra("lng", Float.NaN)
                if (lat.isNaN() || lng.isNaN()) return
                val heading = intent.getFloatExtra("heading", Float.NaN).takeUnless { it.isNaN() }?.toDouble()
                signalHub.onLocationFix(
                    io.majung.pleos.runtime.VehicleLocationReading(
                        supported = true, latitude = lat.toDouble(), longitude = lng.toDouble(), headingDeg = heading, confidence = 0.9,
                        rawSummary = "[DEBUG adb] location(${"%.5f".format(lat)}, ${"%.5f".format(lng)}, heading=${heading ?: "-"})"
                    )
                )
            }
        }
        val filter = android.content.IntentFilter("io.majung.pleos.DEBUG_LOCATION")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, android.content.Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugLocationReceiver = receiver
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
        suggestionNotifier.unregister()
        debugLocationReceiver?.let { runCatching { unregisterReceiver(it) } }
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
