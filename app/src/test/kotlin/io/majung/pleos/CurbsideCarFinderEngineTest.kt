package io.majung.pleos

import io.majung.pleos.finder.CurbsideCarFinderEngine
import io.majung.pleos.finder.ManualOffsetPreset
import io.majung.pleos.finder.PleosTurnSignalState
import io.majung.pleos.model.LocationReliability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 접근 알림의 경계값, 간판 기준 앞뒤 거리 계산, 위치 신뢰도가 낮을 때 숫자를 숨기는 규칙을 검증한다. */
class CurbsideCarFinderEngineTest {

    private val engine = CurbsideCarFinderEngine()
    private val lat = 37.498812
    private val lng = 127.061420
    private val mPerDegLat = 111_320.0
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    @Test
    fun approachNeedsAllThreeOf80MetersSlowSpeedAndRightTurnSignal() {
        assertTrue(engine.shouldTriggerApproachNotice(80, 25f, PleosTurnSignalState.RIGHT).first)
        assertFalse(engine.shouldTriggerApproachNotice(81, 25f, PleosTurnSignalState.RIGHT).first)
        assertFalse(engine.shouldTriggerApproachNotice(80, 26f, PleosTurnSignalState.RIGHT).first)
        assertFalse(engine.shouldTriggerApproachNotice(80, 25f, PleosTurnSignalState.LEFT).first)
    }

    @Test
    fun withoutTurnSignalTheFallbackFiresOnlyWithin50MetersAt20KmhOrLess() {
        assertTrue(engine.shouldTriggerApproachNotice(50, 20f, PleosTurnSignalState.OFF).first)
        assertFalse(engine.shouldTriggerApproachNotice(51, 20f, PleosTurnSignalState.OFF).first)
        assertFalse(engine.shouldTriggerApproachNotice(50, 21f, PleosTurnSignalState.OFF).first)
    }

    @Test
    fun distanceIsZeroAtTheSamePointAnd100MetersFor100MetersNorth() {
        assertEquals(0, engine.distanceMeters(lat, lng, lat, lng))
        assertEquals(100, engine.distanceMeters(lat, lng, lat + 100.0 / mPerDegLat, lng))
        assertEquals(60, engine.distanceMeters(lat, lng, lat, lng + 60.0 / mPerDegLng))
    }

    @Test
    fun longitudinalOffsetIsSignedAlongTheHeadingAndIgnoresSidewaysDisplacement() {
        assertEquals(12, engine.computeLongitudinalOffsetMeters(lat, lng, lat + 12.0 / mPerDegLat, lng, 0.0))
        assertEquals(-10, engine.computeLongitudinalOffsetMeters(lat, lng, lat - 10.0 / mPerDegLat, lng, 0.0))
        assertEquals(12, engine.computeLongitudinalOffsetMeters(lat, lng, lat, lng + 12.0 / mPerDegLng, 90.0))
        // 북쪽을 보고 있을 때 동쪽으로 8 m 벗어난 것은 앞뒤 거리가 아니다
        assertEquals(0, engine.computeLongitudinalOffsetMeters(lat, lng, lat, lng + 8.0 / mPerDegLng, 0.0))
    }

    @Test
    fun guideTextSaysFrontWithin3MetersAndCountsCarLengthsBeyond() {
        assertTrue(engine.formatReliableOffsetGuide("파리바게뜨 간판", 2).contains("정면"))
        assertTrue(engine.formatReliableOffsetGuide("파리바게뜨 간판", 12).contains("앞쪽 약 12m(차 2대 앞)"))
        assertTrue(engine.formatReliableOffsetGuide("파리바게뜨 간판", -10).contains("뒤쪽 약 10m(차 2대 뒤)"))
        assertTrue(engine.formatReliableOffsetGuide("파리바게뜨 간판", 4).contains("차 1대 앞"))
    }

    private fun evaluate(conf: Double?, northMeters: Double = 12.0, heading: Double? = 0.0, preset: ManualOffsetPreset? = null) =
        engine.evaluateCurbsideStop(
            vinCode = "KMH123", fallbackModelName = "아이오닉 9", plateLast4 = "4568", landmarkName = "파리바게뜨 간판",
            spotLat = lat, spotLng = lng, actualLat = lat + northMeters / mPerDegLat, actualLng = lng, headingDeg = heading,
            locationConfidence = conf, distanceMeters = 12, speedKmh = 0f, turnSignal = PleosTurnSignalState.OFF, manualPreset = preset
        )

    @Test
    fun confidenceOf70PercentIsTheLowestThatShowsMeters() {
        val ok = evaluate(conf = 0.70)
        assertEquals(LocationReliability.HIGH_CONFIDENCE_AUTO, ok.reliability)
        assertEquals(12, ok.offsetMeters)

        val low = evaluate(conf = 0.69)
        assertEquals(LocationReliability.LOW_CONFIDENCE_FALLBACK, low.reliability)
        assertNull(low.offsetMeters)
        assertTrue(low.passengerGuideText.contains("번호판 끝 4자리 4568"))
    }

    @Test
    fun missingHeadingOrMoreThan60MetersOffFallsBackToTextWithoutMeters() {
        assertNull(evaluate(conf = 0.9, heading = null).offsetMeters)
        assertNull(evaluate(conf = null).offsetMeters)
        val far = evaluate(conf = 0.9, northMeters = 75.0)
        assertEquals(LocationReliability.LOW_CONFIDENCE_FALLBACK, far.reliability)
        assertNull(far.offsetMeters)
    }

    @Test
    fun driverPresetOverridesEvenAHighConfidenceFix() {
        val r = evaluate(conf = 0.95, preset = ManualOffsetPreset.BEHIND_10M)
        assertEquals(LocationReliability.DRIVER_MANUAL_OVERRIDE, r.reliability)
        assertEquals(-10, r.offsetMeters)
        assertTrue(r.approachTriggered)
    }

    @Test
    fun modelLabelComesFromVinPrefixAndFallsBackToTheDriverSetName() {
        assertEquals("디 올 뉴 싼타페", engine.resolveModelFromVin("KM8ABC", "아이오닉 9"))
        assertEquals("아이오닉 패밀리", engine.resolveModelFromVin("KMH999", "아이오닉 9"))
        assertEquals("기아 패밀리 SUV", engine.resolveModelFromVin("KNA123", "아이오닉 9"))
        assertEquals("아이오닉 9", engine.resolveModelFromVin(null, "아이오닉 9"))
        assertEquals("아이오닉 9", engine.resolveModelFromVin("KN", "아이오닉 9"))
        assertEquals("아이오닉 9", engine.resolveModelFromVin("5YJ3E1", "아이오닉 9"))
    }
}
