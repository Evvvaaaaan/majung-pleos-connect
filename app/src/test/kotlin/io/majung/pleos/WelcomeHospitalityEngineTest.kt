package io.majung.pleos

import io.majung.pleos.welcome.BoardingHospitalityStage
import io.majung.pleos.welcome.PassengerWelcomeProfile
import io.majung.pleos.welcome.WelcomeHospitalityEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 맞이 단계의 안전 규칙: P단과 운전자 확인 없이는 잠금을 풀지 않고, 차도 쪽 문 경고가 모든 것에 우선한다. */
class WelcomeHospitalityEngineTest {

    private val engine = WelcomeHospitalityEngine()
    private val child = PassengerWelcomeProfile("지수", "CHILD", "ROW_1_RIGHT")

    private fun eval(
        profile: PassengerWelcomeProfile = child, park: Boolean = true, checked: Boolean = true, rain: Boolean = false,
        tempC: Double = 15.0, night: Boolean = false, doorOpen: Boolean = false, trafficDoor: Boolean = false,
        seated: Boolean = false, buckled: Boolean = false
    ) = engine.evaluateState(profile, park, checked, rain, tempC, night, doorOpen, trafficDoor, seated, buckled)

    @Test
    fun nothingIsUnlockedBeforeParkAndDriverCheck() {
        val moving = eval(park = false, checked = true)
        assertEquals(BoardingHospitalityStage.APPROACHING, moving.stage)
        assertFalse(moving.unlockPassengerDoorInPark)
        assertEquals(0, moving.frontRightWindowPercent)

        val unchecked = eval(park = true, checked = false)
        assertFalse(unchecked.unlockPassengerDoorInPark)
        assertNull(unchecked.ttsUtterance)
    }

    @Test
    fun parkedAndCheckedUnlocksAndLowersTheWindowTo25Percent() {
        val d = eval()
        assertEquals(BoardingHospitalityStage.CURBSIDE_EYE_CONTACT, d.stage)
        assertTrue(d.unlockPassengerDoorInPark)
        assertEquals(25, d.frontRightWindowPercent)
    }

    @Test
    fun rainOrFreezingKeepsTheWindowShutButStillUnlocks() {
        val rain = eval(rain = true)
        assertTrue(rain.unlockPassengerDoorInPark)
        assertEquals(0, rain.frontRightWindowPercent)
        assertTrue(rain.welcomeSubtitle!!.contains("우천"))

        val freezing = eval(tempC = 0.0)
        assertTrue(freezing.unlockPassengerDoorInPark)
        assertEquals(0, freezing.frontRightWindowPercent)
    }

    @Test
    fun readingLightFollowsLowBeamOnlyOnceTheDoorIsActuallyOpen() {
        assertFalse(eval(night = true).readingLightOn)
        assertTrue(eval(night = true, doorOpen = true).readingLightOn)
        assertFalse(eval(night = false, doorOpen = true).readingLightOn)
    }

    @Test
    fun trafficSideDoorWarningOverridesEverythingIncludingABuckledBelt() {
        val d = eval(trafficDoor = true, seated = true, buckled = true)
        assertTrue(d.trafficSideDoorWarning)
        assertFalse(d.unlockPassengerDoorInPark)
        assertEquals(0, d.frontRightWindowPercent)
        assertFalse(d.burnLocationTokenImmediately)
        assertTrue(d.ttsUtterance!!.contains("차도 방향 왼쪽 문이 열렸습니다"))
    }

    @Test
    fun seatedPassengerIsGreetedByNameAndBlankNameFallsBack() {
        val d = eval(doorOpen = true, seated = true)
        assertEquals(BoardingHospitalityStage.SEATED_WELCOME_GREETING, d.stage)
        assertEquals("지수님, 반갑습니다.", d.welcomeHeadline)
        assertTrue(d.ttsUtterance!!.startsWith("지수님, 반갑습니다."))

        assertEquals("동승자님, 반갑습니다.", eval(profile = child.copy(displayName = "  "), seated = true).welcomeHeadline)
        assertTrue(eval(profile = child.copy(personaType = "SENIOR"), seated = true).welcomeSubtitle!!.contains("천천히"))
    }

    @Test
    fun shareIsBurnedOnlyWhenBeltIsBuckledAndTheDoorIsClosed() {
        val stillOpen = eval(doorOpen = true, seated = true, buckled = true)
        assertFalse(stillOpen.burnLocationTokenImmediately)

        val done = eval(doorOpen = false, seated = true, buckled = true)
        assertEquals(BoardingHospitalityStage.BUCKLED_READY_TO_DEPART, done.stage)
        assertTrue(done.burnLocationTokenImmediately)
        assertEquals(0, done.frontRightWindowPercent)
        assertFalse(done.readingLightOn)
        assertFalse(done.unlockPassengerDoorInPark)
    }
}
