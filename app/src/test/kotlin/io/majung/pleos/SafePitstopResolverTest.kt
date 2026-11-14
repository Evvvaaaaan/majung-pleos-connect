package io.majung.pleos

import io.majung.pleos.model.PassengerProfile
import io.majung.pleos.orchestrator.SafePitstopResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운전자가 말하거나 고른 장소 이름이 정차 후보 지점으로 바뀌는 규칙을 검증한다. */
class SafePitstopResolverTest {

    private val resolver = SafePitstopResolver(
        """{"pitstops":[
            {"id":"p1","keywordAliases":["대치","파리바게뜨"],"rawPinInSchoolZone":true,"landmarkSignboard":"파리바게뜨 간판","candidateSignboards":["파리바게뜨 간판","올리브영 간판"],"pitstopName":"대치 포켓","latitude":37.498812,"longitude":127.061420,"poiId":"poi1","address":"주소1","walkingMetersWithoutCrossing":40},
            {"id":"p2","keywordAliases":["수서역"],"rawPinInSchoolZone":false,"landmarkSignboard":"3번 출구","pitstopName":"수서 포켓","latitude":37.4873,"longitude":127.1012,"poiId":"poi2","address":"주소2","walkingMetersWithoutCrossing":25}
        ]}"""
    )

    @Test
    fun aliasInsideALongerPhraseResolvesToTheRegisteredPitstop() {
        val stop = resolver.resolveKeywordToSafePitstop("파리바게뜨 대치점", passengerName = "지수")
        assertEquals("p1", stop.stopId)
        assertEquals("파리바게뜨 간판", stop.landmarkSignboard)
        assertEquals(listOf("파리바게뜨 간판", "올리브영 간판"), stop.candidateSignboards)
        assertEquals(37.498812, stop.latitude, 0.0)
        assertTrue(stop.rawPinWasInSchoolZone)
        assertEquals(40, stop.walkingMetersWithoutCrossing)
    }

    @Test
    fun pitstopWithoutCandidateListUsesItsLandmarkAsTheOnlyCandidate() {
        val stop = resolver.resolveKeywordToSafePitstop("수서역")
        assertEquals("p2", stop.stopId)
        assertEquals(listOf("3번 출구"), stop.candidateSignboards)
        assertFalse(stop.rawPinWasInSchoolZone)
    }

    @Test
    fun unknownPlaceStillProducesAUsableStopSoThePickupCanStart() {
        val stop = resolver.resolveKeywordToSafePitstop(" 양재 꽃시장 ", passengerName = "하윤")
        assertTrue(stop.stopId.startsWith("ps_dynamic_"))
        assertEquals("양재 꽃시장 정문 우측 간판", stop.landmarkSignboard)
        assertEquals(2, stop.candidateSignboards.size)
        assertEquals("하윤", stop.passengerName)
    }

    @Test
    fun blankPassengerNameFallsBackToALabelForTheProfile() {
        assertEquals("자녀", resolver.resolveKeywordToSafePitstop("대치", passengerName = "", profile = PassengerProfile.CHILD).passengerName)
        assertEquals("어르신", resolver.resolveKeywordToSafePitstop("대치", passengerName = " ", profile = PassengerProfile.SENIOR).passengerName)
    }

    @Test
    fun unknownPlaceForAChildIsTreatedAsASchoolZonePin() {
        assertTrue(resolver.resolveKeywordToSafePitstop("모르는 학원", profile = PassengerProfile.CHILD).rawPinWasInSchoolZone)
        assertFalse(resolver.resolveKeywordToSafePitstop("모르는 역", profile = PassengerProfile.ADULT).rawPinWasInSchoolZone)
    }
}
