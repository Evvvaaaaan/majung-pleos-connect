package io.majung.pleos

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.audio.FocusChangeEvent
import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.audio.VehicleAudioPlatform
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.gleo.GleoProtocolCodec
import io.majung.pleos.model.BoardingDetectionMode
import io.majung.pleos.model.LocationReliability
import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SeatZone
import io.majung.pleos.orchestrator.PickupEtaOrchestrator
import io.majung.pleos.orchestrator.RelayNotificationGateway
import io.majung.pleos.orchestrator.SafePitstopResolver
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.safety.DrivingLoadSnapshot
import io.majung.pleos.safety.GatedPrompt
import io.majung.pleos.safety.PromptPriority
import io.majung.pleos.sdk.SimNaviGateway
import io.majung.pleos.sdk.SimVehicleHardware
import io.majung.pleos.vehicle.PleosCapabilityGateway
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MajungV2VerificationTest {

    @Test
    fun turnSignalAloneFarAwayDoesNotTriggerFalseApproachAlert() {
        val finder = CurbsideCarFinderEngine()
        val (farTriggered, _) = finder.shouldTriggerApproachNotice(
            distanceMeters = 240,
            speedKmh = 48f,
            turnSignal = PleosTurnSignalState.RIGHT
        )
        assertFalse(farTriggered)

        val (nearCompositeTriggered, _) = finder.shouldTriggerApproachNotice(
            distanceMeters = 65,
            speedKmh = 18f,
            turnSignal = PleosTurnSignalState.RIGHT
        )
        assertTrue(nearCompositeTriggered)
    }

    @Test
    fun lowConfidenceGpsHidesMetersAndManualOverrideWinsImmediately() {
        val finder = CurbsideCarFinderEngine()
        val lowConf = finder.evaluateCurbsideStop(
            vinCode = "KMH12345678",
            fallbackModelName = "아이오닉 9",
            plateLast4 = "4568",
            landmarkName = "파리바게뜨 대치점 노란 간판",
            spotLat = 37.498812,
            spotLng = 127.061420,
            actualLat = 37.498910,
            actualLng = 127.061420,
            headingDeg = 0.0,
            locationConfidence = 0.35, // 저신뢰 환경
            distanceMeters = 15,
            speedKmh = 0f,
            turnSignal = PleosTurnSignalState.OFF,
            manualPreset = null
        )
        assertEquals(LocationReliability.LOW_CONFIDENCE_FALLBACK, lowConf.reliability)
        assertNull(lowConf.offsetMeters)

        val overridden = finder.evaluateCurbsideStop(
            vinCode = "KMH12345678",
            fallbackModelName = "아이오닉 9",
            plateLast4 = "4568",
            landmarkName = "파리바게뜨 대치점 노란 간판",
            spotLat = 37.498812,
            spotLng = 127.061420,
            actualLat = null,
            actualLng = null,
            headingDeg = null,
            locationConfidence = 0.10,
            distanceMeters = 15,
            speedKmh = 0f,
            turnSignal = PleosTurnSignalState.OFF,
            manualPreset = ManualOffsetPreset.AHEAD_12M
        )
        assertEquals(LocationReliability.DRIVER_MANUAL_OVERRIDE, overridden.reliability)
        assertEquals(12, overridden.offsetMeters)
    }

    @Test
    fun orchestratorIssues20MinTicketAndImmediatelyShredsOnCompletion() = runTest {
        var shreddedId: String? = null
        val fakeRelay = object : RelayNotificationGateway {
            override suspend fun publishSessionState(
                ticket: PickupSessionShareTicket,
                stop: SafePitstopStop,
                remainingSeconds: Int,
                phase: PickupPhase,
                boardingDoorUnlocked: Boolean,
                welcomeGreetingActive: Boolean
            ): Boolean = true

            override suspend fun sendKidsFallbackSms(stop: SafePitstopStop, smsBody: String): Boolean = true
            override suspend fun shredEphemeralSession(sessionId: String): Boolean {
                shreddedId = sessionId
                return true
            }
        }
        val fakeAudio = AudioFocusCoordinator(
            audioPlatform = object : VehicleAudioPlatform {
                override fun requestTransientMayDuckFocus(onFocusChanged: (FocusChangeEvent) -> Unit) = true
                override fun abandonAudioFocus() {}
            },
            ttsEngine = object : PleosTtsEngine {
                override fun initializeAndSetKoreanLocale(onReady: () -> Unit) = onReady()
                override fun speak(text: String, onCompleted: () -> Unit, onError: (String) -> Unit) = onCompleted()
                override fun stop() {}
                override fun release() {}
            }
        ).also { it.initialize() }

        val resolver = SafePitstopResolver(
            """{"pitstops":[{"id":"p1","keywordAliases":["대치"],"rawPinInSchoolZone":true,"landmarkSignboard":"파리바게뜨 간판","pitstopName":"포켓","latitude":37.4988,"longitude":127.0614,"poiId":"poi1","address":"주소","walkingMetersWithoutCrossing":40}]}"""
        )
        val stop = resolver.resolveKeywordToSafePitstop("대치", passengerName = "지수")
        val orch = PickupEtaOrchestrator(
            capabilityGateway = PleosCapabilityGateway(SimVehicleHardware()),
            drivingLoadGate = DrivingLoadGate(),
            finderEngine = CurbsideCarFinderEngine(),
            audioCoordinator = fakeAudio,
            naviGateway = SimNaviGateway(),
            relayGateway = fakeRelay,
            relayBaseUrl = "http://10.0.2.2:8080"
        )

        val started = orch.startPickupSession(
            stops = listOf(stop),
            vinCode = "KMH999999",
            fallbackModelName = "아이오닉 9",
            plateLast4 = "4568",
            nowMs = 1_000L
        ).getOrThrow()

        assertEquals(20L * 60L * 1000L, started.activeTicket!!.maxExpiresAtEpochMs - 1_000L)
        orch.completeAndShredSessionImmediately(manualConfirmed = true, nowMs = 5_000L)
        assertEquals(started.sessionId, shreddedId)
        assertEquals(PickupPhase.STEP6_COMPLETED_SHREDDED, orch.uiState.value.phase)
    }

    @Test
    fun entryTrimFallbackAndWelcomeGreetingAndGleoParsingWorkCleanly() = runTest {
        val parsed = GleoProtocolCodec.parseActionIntent(
            "START_SAFE_PICKUP",
            "\"{\\\"passengerName\\\":\\\"지수\\\",\\\"passengerProfile\\\":\\\"CHILD\\\",\\\"destinationKeyword\\\":\\\"한빛초교\\\",\\\"hasCarSeat\\\":true}\""
        ).getOrThrow()
        assertEquals(PassengerProfile.CHILD, parsed.passengerProfile)
        assertEquals("지수", parsed.passengerName)

        val simHw = SimVehicleHardware(simulateEntryTrimWithoutRow2Occupancy = true)
        val gateway = PleosCapabilityGateway(simHw)
        val caps = gateway.initializeAndProbe()
        assertEquals(BoardingDetectionMode.DOOR_AND_BELT_SEQUENCE_INFERENCE, caps.boardingDetectionMode)

        val welcome = WelcomeHospitalityEngine().evaluateState(
            profile = PassengerWelcomeProfile("지수", "ADULT", "ROW_1_RIGHT"),
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
        assertEquals("지수님, 반갑습니다.", welcome.welcomeHeadline)
        assertEquals(25, welcome.frontRightWindowPercent)

        val gate = DrivingLoadGate()
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 45f, steeringAngleDeg = 60f), 100L)
        assertTrue(
            gate.submitOrHold(
                GatedPrompt("id1", "cat", "일반 안내", "배너", PromptPriority.HIGH_PICKUP_EVENT, 100L),
                100L
            ).isEmpty()
        )
    }
}
