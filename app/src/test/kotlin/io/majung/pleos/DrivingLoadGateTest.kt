package io.majung.pleos

import io.majung.pleos.safety.DrivingLoadGate
import io.majung.pleos.safety.DrivingLoadSnapshot
import io.majung.pleos.safety.GatedPrompt
import io.majung.pleos.safety.PromptPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 운전 부하 지수(DLI) 게이트: 조향·브레이크·와이퍼·다음 회전 거리로 부하를 계산해
 * 일반 안내는 보류하고, 안전 경고(CRITICAL)만 즉시 통과시키는지 검증한다.
 */
class DrivingLoadGateTest {

    private fun prompt(id: String, priority: PromptPriority, at: Long, category: String = id) =
        GatedPrompt(id, category, "말 $id", "배너 $id", priority, at)

    @Test
    fun stationaryCarHasZeroLoadWhateverTheSteeringAngle() {
        val gate = DrivingLoadGate()
        val dli = gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 0f, steeringAngleDeg = 180f, isBrakePressed = true, nextTbtDistanceMeters = 20))
        assertEquals(0f, dli, 0f)
    }

    @Test
    fun sharpSteeringAloneReachesTheHighLoadThreshold() {
        val gate = DrivingLoadGate()
        assertEquals(0.45f, gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 20f, steeringAngleDeg = -50f)), 0.001f)
        assertEquals(0.45f, gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 20f, steeringRateDegPerSec = 70f)), 0.001f)
    }

    @Test
    fun brakingBelow8KmhAddsNothingAndUpcomingTurnAloneStaysBelowThreshold() {
        val gate = DrivingLoadGate()
        assertEquals(0f, gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 6f, isBrakePressed = true)), 0f)
        assertEquals(0.35f, gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 30f, nextTbtDistanceMeters = 120)), 0.001f)
        // 회전 임박 + 제동이 겹치면 임계값(0.45)을 넘는다
        assertTrue(gate.computeDliScore(DrivingLoadSnapshot(speedKmh = 30f, nextTbtDistanceMeters = 120, isBrakePressed = true)) >= 0.45f)
    }

    @Test
    fun scoreIsCappedAtOne() {
        val gate = DrivingLoadGate()
        val all = DrivingLoadSnapshot(speedKmh = 40f, steeringAngleDeg = 90f, isBrakePressed = true, isWiperHigh = true, nextTbtDistanceMeters = 50, inSchoolZoneCaution = true)
        assertEquals(1f, gate.computeDliScore(all), 0f)
    }

    @Test
    fun normalPromptIsHeldUnderHighLoadAndReleasedAfter1500msOfCalm() {
        val gate = DrivingLoadGate()
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f, steeringAngleDeg = 60f), nowMs = 10_000L)
        assertTrue(gate.submitOrHold(prompt("approach", PromptPriority.HIGH_PICKUP_EVENT, 10_000L), 10_000L).isEmpty())
        assertEquals(1, gate.pendingCount())

        // 부하가 내려가도 1.5초 안정 전에는 내보내지 않는다
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f), nowMs = 11_000L)
        assertFalse(gate.isSafeToDeliverNonCritical(12_000L))
        assertTrue(gate.drainEligiblePrompts(12_000L).isEmpty())

        val released = gate.drainEligiblePrompts(12_500L)
        assertEquals(listOf("approach"), released.map { it.id })
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun criticalInterlockBypassesTheGateEvenUnderFullLoad() {
        val gate = DrivingLoadGate()
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 40f, steeringAngleDeg = 90f, isBrakePressed = true), nowMs = 5_000L)
        val out = gate.submitOrHold(prompt("door", PromptPriority.CRITICAL_INTERLOCK, 5_000L), 5_000L)
        assertEquals(listOf("door"), out.map { it.id })
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun heldPromptsComeOutByPriorityThenAgeAndSameCategoryInfoKeepsOnlyTheLatest() {
        val gate = DrivingLoadGate()
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f, steeringAngleDeg = 60f), nowMs = 1_000L)
        gate.submitOrHold(prompt("eta-old", PromptPriority.NORMAL_INFO, 1_000L, category = "eta"), 1_000L)
        gate.submitOrHold(prompt("eta-new", PromptPriority.NORMAL_INFO, 1_200L, category = "eta"), 1_200L)
        gate.submitOrHold(prompt("suggestion", PromptPriority.HIGH_PICKUP_EVENT, 1_400L), 1_400L)
        assertEquals(2, gate.pendingCount())

        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f), nowMs = 2_000L)
        val released = gate.drainEligiblePrompts(3_500L)
        assertEquals(listOf("suggestion", "eta-new"), released.map { it.id })
    }

    @Test
    fun promptOlderThanItsTtlIsDroppedInsteadOfSpokenLate() {
        val gate = DrivingLoadGate()
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f, steeringAngleDeg = 60f), nowMs = 0L)
        gate.submitOrHold(prompt("stale", PromptPriority.HIGH_PICKUP_EVENT, 0L), 0L)
        gate.updateTelemetry(DrivingLoadSnapshot(speedKmh = 20f), nowMs = 29_000L)
        assertTrue(gate.drainEligiblePrompts(31_000L).isEmpty())
        assertEquals(0, gate.pendingCount())
    }

    @Test
    fun nightThemeHoursAre18To06() {
        val night = io.majung.pleos.ui.MajungTokens::isNightHour
        assertTrue(night(18)); assertTrue(night(23)); assertTrue(night(0)); assertTrue(night(5))
        assertFalse(night(6)); assertFalse(night(12)); assertFalse(night(17))
    }
}
