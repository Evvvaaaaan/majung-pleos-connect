package io.majung.pleos.safety

import java.util.PriorityQueue
import kotlin.math.abs

enum class PromptPriority(val rank: Int) {
    CRITICAL_INTERLOCK(0),
    HIGH_PICKUP_EVENT(1),
    NORMAL_INFO(2)
}

data class GatedPrompt(
    val id: String,
    val categoryKey: String,
    val spokenText: String,
    val bannerTitle: String,
    val priority: PromptPriority,
    val createdAtMs: Long,
    val ttlMs: Long = 30_000L
)

data class DrivingLoadSnapshot(
    val speedKmh: Float = 0f,
    val steeringAngleDeg: Float = 0f,
    val steeringRateDegPerSec: Float = 0f,
    val isBrakePressed: Boolean = false,
    val isWiperHigh: Boolean = false,
    val nextTbtDistanceMeters: Int = Int.MAX_VALUE,
    val inSchoolZoneCaution: Boolean = false,
    val uxRestrictionActive: Boolean = false
)

class DrivingLoadGate(
    private val highLoadThreshold: Float = 0.45f,
    private val stabilizationWindowMs: Long = 1_500L
) {
    private var currentDliScore: Float = 0f
    private var lowLoadSinceMs: Long? = 0L

    private val queue = PriorityQueue<GatedPrompt>(
        compareBy<GatedPrompt> { it.priority.rank }.thenBy { it.createdAtMs }
    )

    fun updateTelemetry(snapshot: DrivingLoadSnapshot, nowMs: Long): Float {
        val computed = computeDliScore(snapshot)
        currentDliScore = computed

        if (computed >= highLoadThreshold) {
            lowLoadSinceMs = null
        } else if (lowLoadSinceMs == null) {
            lowLoadSinceMs = nowMs
        }
        return computed
    }

    fun computeDliScore(s: DrivingLoadSnapshot): Float {
        if (s.speedKmh <= 1.0f) return 0.0f
        var score = 0.0f
        if (abs(s.steeringAngleDeg) >= 45.0f || abs(s.steeringRateDegPerSec) >= 60.0f) score += 0.45f
        if (s.isBrakePressed && s.speedKmh > 8.0f) score += 0.30f
        if (s.isWiperHigh) score += 0.20f
        if (s.nextTbtDistanceMeters in 1..150) score += 0.35f
        if (s.inSchoolZoneCaution) score += 0.25f
        return score.coerceIn(0.0f, 1.0f)
    }

    fun isSafeToDeliverNonCritical(nowMs: Long): Boolean {
        if (currentDliScore >= highLoadThreshold) return false
        val stableSince = lowLoadSinceMs ?: return false
        return (nowMs - stableSince) >= stabilizationWindowMs
    }

    fun submitOrHold(prompt: GatedPrompt, nowMs: Long): List<GatedPrompt> {
        if (prompt.priority == PromptPriority.CRITICAL_INTERLOCK) {
            return listOf(prompt)
        }
        if (prompt.priority == PromptPriority.NORMAL_INFO) {
            queue.removeAll { it.categoryKey == prompt.categoryKey && it.priority == PromptPriority.NORMAL_INFO }
        }
        queue.add(prompt)
        return drainEligiblePrompts(nowMs)
    }

    fun drainEligiblePrompts(nowMs: Long): List<GatedPrompt> {
        queue.removeAll { (nowMs - it.createdAtMs) > it.ttlMs }
        if (!isSafeToDeliverNonCritical(nowMs)) return emptyList()
        val released = mutableListOf<GatedPrompt>()
        while (queue.isNotEmpty()) {
            released.add(queue.poll()!!)
        }
        return released
    }

    fun pendingCount(): Int = queue.size
    fun latestDliScore(): Float = currentDliScore
}
