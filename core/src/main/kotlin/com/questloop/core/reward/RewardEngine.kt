package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.QuestCategory
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Tunable constants for the reward economy (SPEC section 6 & 8).
 *
 * Centralising these makes the economy easy to rebalance and to reason about in
 * tests. Every value here encodes a fairness rule from the spec.
 */
data class RewardConfig(
    /** Per-day ceiling on XP earned from META_MAINTENANCE actions. */
    val metaXpDailyCap: Long = 30,
    /** Decay applied per prior same-day completion of the *same* quest. */
    val antiFarmDecay: Double = 0.55,
    /** Minimum fraction of base XP a repeat can still earn (so it's never literally 0). */
    val antiFarmFloor: Double = 0.1,
    /** Max consistency bonus multiplier added on top of base (e.g. 0.25 = +25%). */
    val maxConsistencyBonus: Double = 0.25,
    /** Streak length at which half of the max consistency bonus is reached. */
    val consistencyHalfLifeDays: Int = 7,
    /** Gentle XP penalty for a genuinely failed/skipped non-reduction quest. */
    val missPenalty: Long = 3,
    /** Per-day ceiling on *total* penalty XP, so a bad day can't spiral. */
    val penaltyDailyCap: Long = 10,
    /** Small honesty reward for truthfully logging a bad-habit relapse. */
    val honestyXp: Long = 3,
)

/**
 * Context the engine needs that depends on the user's recent history. The
 * caller (repository) computes this from stored records; keeping it explicit
 * makes [RewardEngine.score] a pure, deterministic function.
 */
data class RewardContext(
    /** Completions of the *same* quest within [RewardConfig.antiFarmWindowDays] before this one. */
    val priorSameQuestCompletions: Int = 0,
    /** Meta XP already granted today (used to enforce the daily meta cap). */
    val metaXpEarnedToday: Long = 0,
    /** Absolute penalty XP already applied today (used to enforce the penalty cap). */
    val penaltyXpAppliedToday: Long = 0,
    /** Current streak length in days (drives the consistency bonus). */
    val currentStreakDays: Int = 0,
)

/** Result of scoring one completion. [xp] may be negative (gentle penalty). */
data class RewardOutcome(
    val xp: Long,
    val baseXp: Long,
    val multipliers: Map<String, Double>,
    val capReason: String? = null,
    val explanation: String,
)

/**
 * The reward economy. Pure and deterministic: given a [CompletionRecord] and a
 * [RewardContext], it returns the XP delta and a human-readable explanation
 * (SPEC 5 explainability, SPEC 8 fairness).
 */
class RewardEngine(private val config: RewardConfig = RewardConfig()) {

    fun score(record: CompletionRecord, context: RewardContext = RewardContext()): RewardOutcome {
        return when (record.result) {
            CompletionResult.COMPLETED, CompletionResult.PARTIAL -> scorePositive(record, context)
            CompletionResult.SKIPPED, CompletionResult.FAILED -> scoreMissOrRelapse(record, context)
            CompletionResult.RESCHEDULED -> RewardOutcome(
                xp = 0,
                baseXp = 0,
                multipliers = emptyMap(),
                explanation = "Rescheduled — no penalty. Plans change, that's fine.",
            )
        }
    }

    private fun scorePositive(record: CompletionRecord, context: RewardContext): RewardOutcome {
        val fraction = record.fraction.coerceIn(0.0, 1.0)
        val base = (record.difficulty.baseXp * fraction)
        val priorityMult = record.priority.multiplier
        val consistencyMult = 1.0 + consistencyBonus(context.currentStreakDays)
        val antiFarmMult = antiFarmMultiplier(record, context.priorSameQuestCompletions)

        var xp = base * priorityMult * consistencyMult * antiFarmMult
        val multipliers = linkedMapOf(
            "priority" to round2(priorityMult),
            "consistency" to round2(consistencyMult),
            "antiFarm" to round2(antiFarmMult),
        )

        var capReason: String? = null
        if (record.isMeta) {
            // Meta-maintenance is rewarded lightly and capped per day so it can
            // never out-earn real-world progress (SPEC 6 & 7).
            val remaining = max(0L, config.metaXpDailyCap - context.metaXpEarnedToday)
            val grant = xp.roundToLong().coerceAtMost(remaining)
            if (grant < xp.roundToLong()) {
                capReason = "Daily meta-maintenance XP cap reached (${config.metaXpDailyCap})."
            }
            return RewardOutcome(
                xp = grant,
                baseXp = base.roundToLong(),
                multipliers = multipliers,
                capReason = capReason,
                explanation = buildExplanation(record, grant, multipliers, capReason),
            )
        }

        val granted = xp.roundToLong()
        return RewardOutcome(
            xp = granted,
            baseXp = base.roundToLong(),
            multipliers = multipliers,
            capReason = capReason,
            explanation = buildExplanation(record, granted, multipliers, null),
        )
    }

    private fun scoreMissOrRelapse(record: CompletionRecord, context: RewardContext): RewardOutcome {
        // Bad-habit relapse logged honestly is never punished — honesty is
        // rewarded instead (SPEC 6 & 8: reward honesty, don't shame failure).
        if (record.category == QuestCategory.BAD_HABIT_REDUCTION) {
            return RewardOutcome(
                xp = config.honestyXp,
                baseXp = 0,
                multipliers = emptyMap(),
                explanation = "Logged honestly. Recovery and consistency matter more than perfection — " +
                    "+${config.honestyXp} XP for tracking truthfully.",
            )
        }

        // Skipping with no penalty if we'd exceed the daily penalty cap — a bad
        // day should never spiral (SPEC 9 anti-shame).
        val remainingPenalty = max(0L, config.penaltyDailyCap - context.penaltyXpAppliedToday)
        if (remainingPenalty == 0L) {
            return RewardOutcome(
                xp = 0,
                baseXp = 0,
                multipliers = emptyMap(),
                capReason = "Daily penalty cap reached — no further deductions today.",
                explanation = "Tough day. We've stopped deducting XP so you can reset tomorrow.",
            )
        }
        val penalty = config.missPenalty.coerceAtMost(remainingPenalty)
        return RewardOutcome(
            xp = -penalty,
            baseXp = 0,
            multipliers = emptyMap(),
            explanation = "Missed — a gentle -$penalty XP. Tomorrow is a fresh start.",
        )
    }

    /** Smooth bonus that rises with streak length but is capped (no burnout pressure). */
    internal fun consistencyBonus(streakDays: Int): Double {
        if (streakDays <= 0) return 0.0
        val k = config.consistencyHalfLifeDays.toDouble()
        val factor = streakDays / (streakDays + k) // 0..1, =0.5 at half-life
        return config.maxConsistencyBonus * factor
    }

    /**
     * Diminishing returns for repeating the same quest. The first completion in
     * the window earns full value; each repeat decays geometrically toward a
     * floor so easy farming can't dominate progression (SPEC 6 & 8).
     */
    internal fun antiFarmMultiplier(record: CompletionRecord, priorSameQuestCompletions: Int): Double {
        if (priorSameQuestCompletions <= 0) return 1.0
        val decayed = config.antiFarmDecay.pow(priorSameQuestCompletions)
        return max(config.antiFarmFloor, decayed)
    }

    private fun buildExplanation(
        record: CompletionRecord,
        grantedXp: Long,
        multipliers: Map<String, Double>,
        capReason: String?,
    ): String {
        val parts = mutableListOf<String>()
        parts += "+$grantedXp XP for a ${record.difficulty.name.lowercase()} " +
            "${record.category.name.lowercase().replace('_', ' ')} quest"
        if (record.priority.multiplier != 1.0) parts += "priority ×${multipliers["priority"]}"
        if ((multipliers["consistency"] ?: 1.0) > 1.0) parts += "streak ×${multipliers["consistency"]}"
        if ((multipliers["antiFarm"] ?: 1.0) < 1.0) parts += "repeat ×${multipliers["antiFarm"]} (diminishing returns)"
        capReason?.let { parts += it }
        return parts.joinToString("; ") + "."
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
