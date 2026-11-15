package io.majung.pleos

import io.majung.pleos.audio.AudioFocusCoordinator
import io.majung.pleos.audio.FocusChangeEvent
import io.majung.pleos.audio.PleosTtsEngine
import io.majung.pleos.audio.VehicleAudioPlatform
import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.bearingDeg
import io.majung.pleos.finder.offsetPoint
import io.majung.pleos.model.CannotStopOption
import io.majung.pleos.model.GeoPoint
import io.majung.pleos.model.LiveMapState
import io.majung.pleos.model.PassengerSuggestion
import io.majung.pleos.model.PickupPhase
import io.majung.pleos.model.PickupSessionShareTicket
import io.majung.pleos.model.SafePitstopStop
import io.majung.pleos.model.SuggestionKind
import io.majung.pleos.model.VehicleFacts
import io.majung.pleos.orchestrator.NaviRouteGateway
import io.majung.pleos.orchestrator.NaviRouteSlice
import io.majung.pleos.orchestrator.PickupEtaOrchestrator
import io.majung.pleos.orchestrator.RelayNotificationGateway
import io.majung.pleos.orchestrator.SafePitstopResolver
import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.sdk.SimVehicleHardware
import io.majung.pleos.vehicle.PleosCapabilityGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMapAndSuggestionTest {

    private val finder = CurbsideCarFinderEngine()

    private fun dist(a: GeoPoint, b: GeoPoint) = finder.distanceMeters(a.lat, a.lng, b.lat, b.lng)

    private class Harness {
        val published = mutableListOf<LiveMapState>()
        val replies = mutableListOf<Triple<String, String, Boolean>>()
        val routes = mutableListOf<NaviRouteSlice>()
        val spoken = mutableListOf<String>()

        val relay = object : RelayNotificationGateway {
            override suspend fun publishSessionState(
                ticket: PickupSessionShareTicket, stop: SafePitstopStop, remainingSeconds: Int, phase: PickupPhase,
                boardingDoorUnlocked: Boolean, welcomeGreetingActive: Boolean, facts: VehicleFacts, liveMap: LiveMapState
            ): Boolean { published += liveMap; return true }
            override suspend fun replySuggestion(sessionId: String, suggestionId: String, accepted: Boolean): Boolean {
                replies += Triple(sessionId, suggestionId, accepted); return true
            }
            override suspend fun sendKidsFallbackSms(stop: SafePitstopStop, smsBody: String) = true
            override suspend fun shredEphemeralSession(sessionId: String) = true
        }
        val navi = object : NaviRouteGateway {
            override suspend fun dispatchRouteWithWaypoints(slice: NaviRouteSlice): Boolean { routes += slice; return true }
            override suspend fun cancelActiveRoute() = true
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
            clockMs = { 0L }
        ).also { it.initialize() }
        val orchestrator = PickupEtaOrchestrator(
            PleosCapabilityGateway(SimVehicleHardware()), DrivingLoadGate(), CurbsideCarFinderEngine(), audio, navi, relay,
            "http://10.0.2.2:8080", "[TEST]"
        )
        val stop: SafePitstopStop = SafePitstopResolver(
            """{"pitstops":[{"id":"p1","keywordAliases":["대치"],"rawPinInSchoolZone":false,"landmarkSignboard":"파리바게뜨 간판","candidateSignboards":["파리바게뜨 간판"],"pitstopName":"포켓","latitude":37.498812,"longitude":127.061420,"poiId":"poi1","address":"주소","walkingMetersWithoutCrossing":40}]}"""
        ).resolveKeywordToSafePitstop("대치", passengerName = "지수")
        val pickup = GeoPoint(stop.latitude, stop.longitude)

        suspend fun start() {
            orchestrator.startPickupSession(listOf(stop), "KMH999", "아이오닉 9", "4568", 1_000L).getOrThrow()
        }
    }

    @Test
    fun geoMathOffsetIs30MetersAndBearingRoundTrips() {
        val o = GeoPoint(37.4988, 127.0614)
        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 270.0, 333.0)) {
            val p = offsetPoint(o, bearing, 30.0)
            val d = dist(o, p)
            assertTrue("bearing $bearing -> $d m", d in 29..31)
            val back = bearingDeg(o, p)
            assertEquals(bearing, back, 0.5)
        }
        assertEquals(90.0, bearingDeg(o, GeoPoint(o.lat, o.lng + 0.001)), 0.01)
    }

    @Test
    fun startPublishesPickupFieldsAndCarFieldsAreThrottledTo2Seconds() = runTest {
        val h = Harness()
        h.start()
        val first = h.published.single()
        assertEquals(h.pickup, first.pickup)
        assertEquals("파리바게뜨 간판", first.pickupLabel)
        assertEquals(1, first.pickupVersion)

        val car = GeoPoint(37.4980, 127.0614)
        h.orchestrator.updateCarLocation(car, 12.0, 2_000L) // 시작 publish 후 1초 → 보내지 않음
        assertEquals(1, h.published.size)
        assertEquals(car, h.orchestrator.uiState.value.liveMap.car)
        h.orchestrator.updateCarLocation(car, 12.0, 3_100L) // 2초 경과 → 보냄
        assertEquals(2, h.published.size)
        val sent = h.published.last()
        assertEquals(car, sent.car)
        assertEquals(12.0, sent.carHeadingDeg!!, 0.0)
        assertEquals(3_100L, sent.carUpdatedAtMs)
        h.orchestrator.updateCarLocation(car, 12.0, 3_500L)
        assertEquals(2, h.published.size)
    }

    @Test
    fun passengerLocationUpdatesMapWithoutPublishing() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.onPassengerLocation(GeoPoint(37.49885, 127.0615), 15.0, 5_000L)
        val m = h.orchestrator.uiState.value.liveMap
        assertEquals(GeoPoint(37.49885, 127.0615), m.passenger)
        assertEquals(15.0, m.passengerAccuracyM!!, 0.0)
        assertEquals(1, h.published.size)
    }

    @Test
    fun cannotStopAhead30MovesPickup30MetersAlongHeading() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.updateCarLocation(GeoPoint(37.4980, 127.0614), 90.0, 1_500L) // 동쪽을 향함
        assertTrue(h.orchestrator.applyCannotStopOption(CannotStopOption.AHEAD_30M, 2_000L))
        val s = h.orchestrator.uiState.value
        val moved = s.liveMap.pickup!!
        assertEquals(30.0, dist(h.pickup, moved).toDouble(), 1.0)
        assertEquals(90.0, bearingDeg(h.pickup, moved), 0.5)
        assertEquals(2, s.liveMap.pickupVersion)
        assertEquals(CannotStopOption.AHEAD_30M.labelKo, s.liveMap.pickupLabel)
        assertEquals(moved.lat, s.currentStop!!.latitude, 0.0)
        assertEquals(moved.lng, s.currentStop!!.longitude, 0.0)
        assertEquals(moved.lat, h.routes.last().finalDestination.latitude, 0.0)
        assertEquals(2, h.published.last().pickupVersion) // 즉시 publish
    }

    @Test
    fun cannotStopOtherSideIs14MetersToTheLeftOfHeading() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.updateCarLocation(GeoPoint(37.4980, 127.0614), 0.0, 1_500L) // 북쪽을 향함 → 왼쪽은 서쪽(270°)
        assertTrue(h.orchestrator.applyCannotStopOption(CannotStopOption.OTHER_SIDE_UTURN, 2_000L))
        val s = h.orchestrator.uiState.value
        val moved = s.liveMap.pickup!!
        assertEquals(14.0, dist(h.pickup, moved).toDouble(), 1.0)
        assertEquals(270.0, bearingDeg(h.pickup, moved), 0.5)
        assertEquals("건너편에서 태울게요 (U턴)", s.liveMap.pickupNote)
        assertEquals(2, s.liveMap.pickupVersion)
        assertEquals(1, h.routes.size - 1) // 시작 시 1회 + 재경로 1회
    }

    @Test
    fun cannotStopLoopAroundKeepsPointAndSetsNote() = runTest {
        val h = Harness()
        h.start()
        assertTrue(h.orchestrator.applyCannotStopOption(CannotStopOption.LOOP_AROUND, 2_000L))
        val s = h.orchestrator.uiState.value
        assertEquals(h.pickup, s.liveMap.pickup)
        assertEquals("한 바퀴 돌고 다시 올게요 (약 3분)", s.liveMap.pickupNote)
        assertEquals("한 바퀴 돌고 다시 올게요 (약 3분)", h.published.last().pickupNote)
    }

    @Test
    fun cannotStopIsRefusedWithoutActiveSession() = runTest {
        val h = Harness()
        assertFalse(h.orchestrator.applyCannotStopOption(CannotStopOption.AHEAD_30M, 1_000L))
        assertTrue(h.published.isEmpty())
    }

    private fun suggestion(kind: SuggestionKind, point: GeoPoint? = null) =
        PassengerSuggestion("abc123", kind, "여기서 탈게요", point, null, 7_000L)

    @Test
    fun acceptingPickupHereMovesPickupToPassengerAndRepliesAccepted() = runTest {
        val h = Harness()
        h.start()
        val here = GeoPoint(37.49895, 127.06160)
        h.orchestrator.onPassengerSuggestion(suggestion(SuggestionKind.PICKUP_HERE, here))
        assertNotNull(h.orchestrator.uiState.value.pendingSuggestion)
        assertTrue(h.spoken.any { it.contains("지수님 제안: 여기서 탈게요") })

        h.orchestrator.resolvePendingSuggestion(accept = true)
        val s = h.orchestrator.uiState.value
        assertNull(s.pendingSuggestion)
        assertEquals(here, s.liveMap.pickup)
        assertEquals(2, s.liveMap.pickupVersion)
        assertEquals("동승자 위치에서 태웁니다", s.liveMap.pickupNote)
        assertEquals(here.lat, s.currentStop!!.latitude, 0.0)
        assertEquals(here.lat, h.routes.last().finalDestination.latitude, 0.0)
        assertEquals(listOf(Triple(s.sessionId, "abc123", true)), h.replies)
        assertEquals(here, h.published.last().pickup)
    }

    @Test
    fun decliningLeavesPickupUntouchedAndRepliesDeclined() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.onPassengerSuggestion(suggestion(SuggestionKind.PICKUP_HERE, GeoPoint(37.49895, 127.0616)))
        h.orchestrator.resolvePendingSuggestion(accept = false)
        val s = h.orchestrator.uiState.value
        assertNull(s.pendingSuggestion)
        assertEquals(h.pickup, s.liveMap.pickup)
        assertEquals(1, s.liveMap.pickupVersion)
        assertEquals(1, h.routes.size)
        assertEquals(false, h.replies.single().third)
    }

    @Test
    fun pickupHereShowsProposedSpotOnCarMapUntilTheDriverAnswers() = runTest {
        val h = Harness()
        h.start()
        val here = GeoPoint(37.49893, 127.06205)
        h.orchestrator.onPassengerSuggestion(suggestion(SuggestionKind.PICKUP_HERE, here))
        // 수락 전: 승차 지점은 그대로, 고른 자리만 따로 표시된다
        assertEquals(here, h.orchestrator.uiState.value.liveMap.proposed)
        assertEquals(h.pickup, h.orchestrator.uiState.value.liveMap.pickup)

        h.orchestrator.resolvePendingSuggestion(accept = false)
        assertNull(h.orchestrator.uiState.value.liveMap.proposed)
    }

    @Test
    fun suggestionsWithoutASpotNeverPlaceAProposedMarker() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.onPassengerSuggestion(PassengerSuggestion("w1", SuggestionKind.WAIT_MINUTES, "2분만 기다려 주세요", null, null, 7_000L))
        assertNull(h.orchestrator.uiState.value.liveMap.proposed)
        h.orchestrator.resolvePendingSuggestion(accept = true)
        val s = h.orchestrator.uiState.value
        assertEquals(h.pickup, s.liveMap.pickup)
        assertEquals("동승자: 2분만 기다려 주세요", s.liveMap.pickupNote)
        assertEquals(true, h.replies.single().third)
    }

    @Test
    fun sessionEndClearsPendingCardPassengerPositionPickupAndProposedSpot() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.onPassengerLocation(GeoPoint(37.4989, 127.0617), 12.0, 5_000L)
        h.orchestrator.onPassengerSuggestion(suggestion(SuggestionKind.PICKUP_HERE, GeoPoint(37.49893, 127.06205)))

        assertTrue(h.orchestrator.completeAndShredSessionImmediately(manualConfirmed = true, nowMs = 9_000L))
        val s = h.orchestrator.uiState.value
        assertEquals(PickupPhase.STEP6_COMPLETED_SHREDDED, s.phase)
        assertNull(s.pendingSuggestion)
        assertNull(s.liveMap.passenger)
        assertNull(s.liveMap.pickup)
        assertNull(s.liveMap.proposed)
    }

    @Test
    fun readyMessageShowsABannerAndNeedsNoAnswer() = runTest {
        val h = Harness()
        h.start()
        h.orchestrator.onPassengerSuggestion(PassengerSuggestion("r1", SuggestionKind.READY, "지금 나가요", null, null, 7_000L))
        val s = h.orchestrator.uiState.value
        assertEquals("지수님이 준비 완료를 알렸어요", s.lastBannerMessage)
        assertNull(s.liveMap.proposed)
        assertTrue(h.spoken.any { it.contains("지수님 제안: 지금 나가요") })
    }

    @Test
    fun headsUpNotificationTextAddsDistanceOnlyForAChosenSpot() {
        val here = suggestion(SuggestionKind.PICKUP_HERE, GeoPoint(37.49893, 127.06205))
        assertEquals("여기서 탈게요 · 승차 지점에서 57 m", io.majung.pleos.ui.SuggestionNotifier.textFor(here, 57))
        assertEquals("여기서 탈게요", io.majung.pleos.ui.SuggestionNotifier.textFor(here, null))
        val ready = PassengerSuggestion("r1", SuggestionKind.READY, "지금 나가요", null, null, 7_000L)
        assertEquals("지금 나가요", io.majung.pleos.ui.SuggestionNotifier.textFor(ready, 12))
        assertEquals("지수님의 제안", io.majung.pleos.ui.SuggestionNotifier.titleFor("지수"))
        assertEquals("동승자님의 제안", io.majung.pleos.ui.SuggestionNotifier.titleFor(" "))
    }
}
