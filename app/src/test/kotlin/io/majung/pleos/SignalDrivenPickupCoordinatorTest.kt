package io.majung.pleos

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.audio.FocusChangeEvent
import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.audio.VehicleAudioPlatform
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.GearPosition
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SeatZone
import io.majung.pleos.model.VehicleFacts
import io.majung.pleos.orchestrator.PickupEtaOrchestrator
import io.majung.pleos.orchestrator.PickupInputs
import io.majung.pleos.orchestrator.RelayNotificationGateway
import io.majung.pleos.orchestrator.SafePitstopResolver
import io.majung.pleos.orchestrator.SignalDrivenPickupCoordinator
import io.majung.pleos.runtime.VehicleLocationReading
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.sdk.SimNaviGateway
import io.majung.pleos.sdk.SimVehicleHardware
import io.majung.pleos.vehicle.BoardingActuator
import io.majung.pleos.vehicle.PleosCapabilityGateway
import io.majung.pleos.vehicle.VehicleSignalHub
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 차량 신호 → 자동 흐름 검증. 모든 신호는 SimVehicleHardware.emit()으로 리스너(VehicleSignalHub)에 주입되므로
 * pleos 플레이버에서 SDK register* 콜백이 호출하는 것과 동일한 경로를 탄다.
 */
class SignalDrivenPickupCoordinatorTest {

    private class Harness {
        var now = 1_000L
        val spoken = mutableListOf<String>()
        val published = mutableListOf<Pair<PickupPhase, VehicleFacts>>()
        var shreddedId: String? = null
        var unlocked = false
        var window = -1
        var light = false

        val relay = object : RelayNotificationGateway {
            override suspend fun publishSessionState(
                ticket: PickupSessionShareTicket, stop: SafePitstopStop, remainingSeconds: Int, phase: PickupPhase,
                boardingDoorUnlocked: Boolean, welcomeGreetingActive: Boolean, facts: VehicleFacts
            ): Boolean { published += phase to facts; return true }
            override suspend fun sendKidsFallbackSms(stop: SafePitstopStop, smsBody: String) = true
            override suspend fun shredEphemeralSession(sessionId: String): Boolean { shreddedId = sessionId; return true }
        }
        val audio = AudioFocusCoordinator(
            audioPlatform = object : VehicleAudioPlatform {
                override fun requestTransientMayDuckFocus(onFocusChanged: (FocusChangeEvent) -> Unit) = true
                override fun abandonAudioFocus() {}
            },
            ttsEngine = object : PleosTtsEngine {
                override fun initializeAndSetKoreanLocale(onReady: () -> Unit) = onReady()
                override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) { spoken += text; onCompleted() }
                override fun stop() {}
                override fun release() {}
            },
            clockMs = { now }
        ).also { it.initialize() }
        val actuator = object : BoardingActuator {
            override fun setPassengerDoorUnlocked(unlocked: Boolean, onResult: (Boolean, String) -> Unit) { this@Harness.unlocked = unlocked; onResult(unlocked, "unlock=$unlocked") }
            override fun setFrontPassengerWindowPercent(targetPercent: Int, onResult: (Int, String) -> Unit) { window = targetPercent; onResult(targetPercent, "window=$targetPercent") }
            override fun setPassengerReadingLight(enabled: Boolean, onResult: (Boolean, String) -> Unit) { light = enabled; onResult(enabled, "light=$enabled") }
        }
        val hw = SimVehicleHardware()
        val gateway = PleosCapabilityGateway(hw)
        val finder = CurbsideCarFinderEngine()
        val orchestrator = PickupEtaOrchestrator(gateway, DrivingLoadGate(), finder, audio, SimNaviGateway(), relay, "http://10.0.2.2:8080", "[TEST]")
        val coordinator = SignalDrivenPickupCoordinator(orchestrator, gateway, finder, WelcomeHospitalityEngine(), audio, actuator, "[TEST]") { now }
        val hub = VehicleSignalHub { now }
        val stop: SafePitstopStop = SafePitstopResolver(
            """{"pitstops":[{"id":"p1","keywordAliases":["대치"],"rawPinInSchoolZone":true,"landmarkSignboard":"파리바게뜨 간판","candidateSignboards":["파리바게뜨 간판"],"pitstopName":"포켓","latitude":37.498812,"longitude":127.061420,"poiId":"poi1","address":"주소","walkingMetersWithoutCrossing":40}]}"""
        ).resolveKeywordToSafePitstop("대치", passengerName = "지수")

        init {
            hw.registerTelemetryCallbacks(hub)
            coordinator.inputs = PickupInputs("KMH999", "아이오닉 9", "4568", PassengerWelcomeProfile("지수", "ADULT", "ROW_1_RIGHT"))
        }

        suspend fun start() {
            gateway.initializeAndProbe()
            orchestrator.startPickupSession(listOf(stop), "KMH999", "아이오닉 9", "4568", now).getOrThrow()
            coordinator.resetForNewSession()
        }

        /** 신호를 주입하고 코디네이터에 전달 (실제 앱에서는 StateFlow collect가 이 역할) */
        suspend fun signal(advanceMs: Long = 1_000L, block: io.majung.pleos.vehicle.VehicleTelemetryListener.() -> Unit) {
            now += advanceMs
            hw.emit(block)
            coordinator.onSignal(hub.state.value)
        }

        suspend fun fix(southMeters: Double, speed: Float, turn: PleosTurnSignalState = PleosTurnSignalState.OFF, conf: Double = 0.9) {
            now += 1_000L
            hub.onLocationFix(VehicleLocationReading(true, stop.latitude - southMeters / 111_320.0, stop.longitude, 0.0, conf, "fix"))
            hw.emit { onSpeedChanged(speed); onTurnSignalChanged(turn) }
            coordinator.onSignal(hub.state.value)
        }
    }

    @Test
    fun approachIsAnnouncedOnlyWithinRadiusAndRightTurnSignal() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.D) }
        h.fix(300.0, 45f, PleosTurnSignalState.RIGHT) // 멀리서 깜빡이만 → 오작동 방지
        assertEquals(PickupPhase.STEP1_LANDMARK_AGREED, h.orchestrator.uiState.value.phase)
        h.fix(70.0, 20f, PleosTurnSignalState.RIGHT)  // 80m 이내 + 감속 + 우측 → 접근
        assertEquals(PickupPhase.STEP2_APPROACHING, h.orchestrator.uiState.value.phase)
        assertTrue(h.spoken.any { it.contains("접근 중") })
    }

    @Test
    fun stoppedInParkIsTheOnlyThingThatMovesToStep4AndPublishesFactToWeb() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.D) }
        h.fix(70.0, 20f, PleosTurnSignalState.RIGHT)
        h.fix(-12.0, 0f) // 간판 12m 앞, 차속 0 — 하지만 아직 D단
        assertEquals(PickupPhase.STEP2_APPROACHING, h.orchestrator.uiState.value.phase)
        assertFalse(h.published.last().second.stoppedInPark)
        h.now += 4_000L   // 3초 주기 위치 재전송이 일어나도(차속 0, D단) 4단계로 넘어가면 안 된다
        h.fix(-12.0, 0f)
        assertEquals(PickupPhase.STEP2_APPROACHING, h.orchestrator.uiState.value.phase)

        h.signal { onGearChanged(GearPosition.P) } // P단 → 정차 확정
        val s = h.orchestrator.uiState.value
        assertEquals(PickupPhase.STEP4_CURBSIDE_STOPPED, s.phase)
        assertTrue(h.published.last().second.stoppedInPark)
        assertEquals(12, s.activeTicket!!.offsetMeters) // 신뢰도 0.9 → 자동 오프셋 "앞 12m"
    }

    @Test
    fun surroundingsCheckIsRefusedUnlessParkedAndThenUnlocksAndOpensWindow() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.D); onSpeedChanged(15f) }
        val refused = h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        assertTrue(refused.isFailure)
        assertFalse(h.unlocked)

        h.signal { onSpeedChanged(0f); onGearChanged(GearPosition.P) }
        val ok = h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        assertTrue(ok.isSuccess)
        assertTrue(h.unlocked)
        assertEquals(25, h.window)
        assertEquals(PickupPhase.STEP5_BOARDING_WELCOME, h.orchestrator.uiState.value.phase)
    }

    @Test
    fun rainKeepsWindowClosedButStillUnlocks() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.P); onWiperHighChanged(true) }
        assertTrue(h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value).isSuccess)
        assertTrue(h.unlocked)
        assertEquals(0, h.window)
    }

    @Test
    fun trafficSideDoorTriggersImmediateCriticalWarning() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.P) }
        h.spoken.clear()
        h.signal { onDoorStateChanged(SeatZone.ROW_2_LEFT, true, 0L) }
        assertTrue(h.spoken.last().contains("차도 방향 왼쪽 문"))
        assertTrue(h.published.last().second.trafficSideDoorOpen)
    }

    @Test
    fun doorSeatBeltSequenceGreetsThenAutoShredsSession() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.P); onLowBeamChanged(true) } // 저녁 픽업: 하향등 ON
        h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        val z = SeatZone.ROW_1_RIGHT

        h.signal { onDoorStateChanged(z, true, h.now) }
        assertTrue(h.light) // 문 열림 → 독서등
        h.signal(2_000L) { onSeatOccupancyChanged(z, true) }
        assertTrue(h.spoken.any { it.startsWith("지수님, 반갑습니다.") })
        assertTrue(h.orchestrator.uiState.value.welcomeGreetingActive)
        assertNull(h.shreddedId)

        h.signal(2_000L) { onDoorStateChanged(z, false, h.now) }
        assertNull(h.shreddedId) // 문만 닫힘 — 아직 아님
        h.signal(1_500L) { onSeatBeltChanged(z, true, h.now) }
        assertNotNull(h.shreddedId) // 벨트 체결 → 자동 파기
        assertEquals(PickupPhase.STEP6_COMPLETED_SHREDDED, h.orchestrator.uiState.value.phase)
        assertEquals(0, h.window)
        assertFalse(h.light)
        assertTrue(h.published.last().second.passengerBuckled)
    }

    @Test
    fun shiftingToDriveWithDoorOpenRaisesDepartureHazard() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.P) }
        h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        h.signal { onDoorStateChanged(SeatZone.ROW_1_RIGHT, true, h.now) }
        h.spoken.clear()
        h.signal { onGearChanged(GearPosition.D) }
        assertTrue(h.spoken.any { it.contains("도어가 아직 열려") })
    }

    @Test
    fun approachPreconditionsCabinForChildCarSeatAndCompletionRestoresIt() = runTest {
        val h = Harness()
        h.coordinator.inputs = PickupInputs("KMH999", "아이오닉 9", "4568", PassengerWelcomeProfile("지수", "CHILD", "ROW_1_RIGHT", hasChildCarSeat = true))
        h.start()
        val z = SeatZone.ROW_1_RIGHT
        assertEquals(2, h.hw.readHvacFanSpeed(z))
        assertEquals(1, h.hw.readSeatHeaterLevel(z))

        h.signal { onGearChanged(GearPosition.D) }
        h.fix(70.0, 20f, PleosTurnSignalState.RIGHT) // 접근 감지 → 사전 공조
        assertEquals(3, h.hw.readHvacFanSpeed(z))      // 풍량 3 (ROW_1_RIGHT 범위 1..5)
        assertEquals(0, h.hw.readSeatHeaterLevel(z))   // 유아 카시트 → 열선 0 고정
        assertTrue(h.coordinator.trace.value.cabinNote!!.contains("카시트"))

        h.signal { onGearChanged(GearPosition.P) }
        h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        h.coordinator.manualCompleteAndShred()
        assertEquals(2, h.hw.readHvacFanSpeed(z))      // 세션 전 상태로 원복
        assertEquals(1, h.hw.readSeatHeaterLevel(z))
    }

    @Test
    fun readingLightOnlyWhenLowBeamIsOn() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.P); onLowBeamChanged(false) }
        h.coordinator.requestSurroundingsCheckedUnlock(h.hub.state.value)
        h.signal { onDoorStateChanged(SeatZone.ROW_1_RIGHT, true, h.now) }
        assertFalse(h.light) // 주간 → 독서등 생략

        val n = Harness()
        n.start()
        n.signal { onGearChanged(GearPosition.P); onLowBeamChanged(true) }
        n.coordinator.requestSurroundingsCheckedUnlock(n.hub.state.value)
        n.signal { onDoorStateChanged(SeatZone.ROW_1_RIGHT, true, n.now) }
        assertTrue(n.light) // 야간 → 독서등 점등
    }

    @Test
    fun approachPromptIsHeldWhileTurnIsImminentAndBraking() = runTest {
        val h = Harness()
        h.start()
        h.signal { onGearChanged(GearPosition.D); onNextTurnDistanceChanged(100); onBrakePedalChanged(true) }
        h.fix(70.0, 20f, PleosTurnSignalState.RIGHT) // 접근 감지되지만 DLI(제동 0.30 + TBT 0.35) ≥ 0.45 → 보류
        assertEquals(PickupPhase.STEP2_APPROACHING, h.orchestrator.uiState.value.phase)
        assertFalse(h.spoken.any { it.contains("접근 중") })

        h.signal { onNextTurnDistanceChanged(Int.MAX_VALUE); onBrakePedalChanged(false) } // 회전 종료·제동 해제
        h.signal(2_000L) { onSpeedChanged(20f) }                                          // 1.5초 안정 후 재생
        assertTrue(h.spoken.any { it.contains("접근 중") })
    }

    @Test
    fun hubDerivesSpeedFromLocationWhenAaosSpeedIsAbsent() {
        var t = 0L
        val hub = VehicleSignalHub { t }
        hub.onLocationFix(VehicleLocationReading(true, 37.0, 127.0, 0.0, 0.9, "a"))
        t = 1_000L
        hub.onLocationFix(VehicleLocationReading(true, 37.0 + 10.0 / 111_320.0, 127.0, 0.0, 0.9, "b")) // 10 m / 1 s = 36 km/h
        val s = hub.state.value
        assertEquals(io.majung.pleos.vehicle.SpeedSource.LOCATION_DERIVED, s.speedSource)
        assertEquals(36f, s.speedKmh, 0.5f)
        hub.onSpeedChanged(12f)
        assertEquals(io.majung.pleos.vehicle.SpeedSource.AAOS_PROPERTY, hub.state.value.speedSource)
    }
}
