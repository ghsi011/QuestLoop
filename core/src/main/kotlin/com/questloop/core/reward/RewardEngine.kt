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
    /**
     * Per-day ceiling on *total* honesty XP, so logging many relapses can't be
     * farmed into unlimited XP (SPEC 8 anti-farming). Honesty is still encouraged
     * — it's just bounded.
     */
    val honestyXpDailyCap: Long = 9,
    /**
     * Per-day ceiling on combined XP from low-effort (trivial/easy) quests, so
     * completing many quick distinct quests can't out-earn real progress. The
     * same-quest [antiFarmDecay] handles repeats of one quest; this handles the
     * many-distinct-trivial-quests case (SPEC 6 & 8).
     */
    val lowEffortXpDailyCap: Long = 40,
    /**
     * Max multiplier bonus for logging *past* a measured quest's target when it
     * allows over-completion (e.g. 0.5 = up to +50% for a big over-shoot). The
     * bonus rises with the overshoot but is capped and diminishing, so it can't be
     * farmed (SPEC §8).
     */
    val overCompletionMaxBonus: Double = 0.5,
    /** Overshoot (in extra targets, i.e. `fraction - 1`) at which half the max
     *  over-completion bonus is reached. 1.0 = one extra full target ⇒ +½ of max. */
    val overCompletionHalfTargets: Double = 1.0,
)

/**
 * Context the engine needs that depends on the user's recent history. The
 * caller (repository) computes this from stored records; keeping it explicit
 * makes [RewardEngine.score] a pure, deterministic function.
 */
data class RewardContext(
    /** Completions of the *same* quest earlier the same day, before this one (drives anti-farm decay). */
    val priorSameQuestCompletions: Int = 0,
    /** Meta XP already granted today (used to enforce the daily meta cap). */
    val metaXpEarnedToday: Long = 0,
    /** Absolute penalty XP already applied today (used to enforce the penalty cap). */
    val penaltyXpAppliedToday: Long = 0,
    /** Honesty XP already granted today (used to enforce the daily honesty cap). */
    val honestyXpEarnedToday: Long = 0,
    /** XP from low-effort quests already granted today (used to enforce the low-effort cap). */
    val lowEffortXpEarnedToday: Long = 0,
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
        // Effort toward the target earns base XP (capped at 100%); effort *past* the
        // target (fraction > 1, only possible for over-completion quests) earns a
        // separate diminishing bonus instead of unbounded linear XP.
        val targetFraction = record.fraction.coerceIn(0.0, 1.0)
        val base = (record.difficulty.baseXp * targetFraction)
        val priorityMult = record.priority.multiplier
        val consistencyMult = 1.0 + consistencyBonus(context.currentStreakDays)
        val antiFarmMult = antiFarmMultiplier(record, context.priorSameQuestCompletions)
        val overMult = 1.0 + overCompletionBonus(record.fraction)

        var xp = base * priorityMult * consistencyMult * antiFarmMult * overMult
        val multipliers = linkedMapOf(
            "priority" to round2(priorityMult),
            "consistency" to round2(consistencyMult),
            "antiFarm" to round2(antiFarmMult),
        )
        if (overMult > 1.0) multipliers["overCompletion"] = round2(overMult)

        var capReason: String? = null
        if (record.isMeta) {
            // Meta-maintenance is rewarded lightly and capped per day so it can
            // never out-earn real-world progress (SPEC 6 & 7).
            val remaining = max(0L, config.metaXpDailyCap - context.metaXpEarnedToday)
            val grant = xp.roundToLong().coerceAtMost(remaining)
            if (grant < xp.roundToLong() || remaining == 0L) {
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

        var granted = xp.roundToLong()
        // Low-effort (trivial/easy) quests share a daily XP ceiling so a pile of
        // quick distinct quests can't dominate progression (SPEC 6 & 8). Repeats of
        // a single quest are already damped by antiFarmMultiplier above.
        if (record.difficulty.isLowEffort) {
            val remaining = max(0L, config.lowEffortXpDailyCap - context.lowEffortXpEarnedToday)
            val capped = granted.coerceAtMost(remaining)
            if (capped < granted) {
                capReason = "Daily easy-quest XP cap reached (${config.lowEffortXpDailyCap})."
            }
            granted = capped
        }
        return RewardOutcome(
            xp = granted,
            baseXp = base.roundToLong(),
            multipliers = multipliers,
            capReason = capReason,
            explanation = buildExplanation(record, granted, multipliers, capReason),
        )
    }

    private fun scoreMissOrRelapse(record: CompletionRecord, context: RewardContext): RewardOutcome {
        // Bad-habit relapse logged honestly is never punished — honesty is
        // rewarded instead (SPEC 6 & 8: reward honesty, don't shame failure). The
        // grant is capped per day so it can't be farmed into unlimited XP.
        if (record.category == QuestCategory.BAD_HABIT_REDUCTION) {
            val remaining = max(0L, config.honestyXpDailyCap - context.honestyXpEarnedToday)
            val grant = config.honestyXp.coerceAtMost(remaining)
            if (grant == 0L) {
                return RewardOutcome(
                    xp = 0,
                    baseXp = 0,
                    multipliers = emptyMap(),
                    capReason = "Daily honesty XP cap reached (${config.honestyXpDailyCap}).",
                    explanation = "Logged honestly — thank you. We've already credited honesty today; " +
                        "tracking truthfully still matters more than points.",
                )
            }
            val capReason = if (grant < config.honestyXp) {
                "Daily honesty XP cap reached (${config.honestyXpDailyCap})."
            } else {
                null
            }
            return RewardOutcome(
                xp = grant,
                baseXp = 0,
                multipliers = emptyMap(),
                capReason = capReason,
                explanation = "Logged honestly. Recovery and consistency matter more than perfection — " +
                    "+$grant XP for tracking truthfully.",
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
        // Name the outcome the user actually chose: a "Skip" tap must not be echoed
        // back as "Missed" (same gentle, capped penalty either way).
        val label = if (record.result == CompletionResult.SKIPPED) "Skipped" else "Missed"
        return RewardOutcome(
            xp = -penalty,
            baseXp = 0,
            multipliers = emptyMap(),
            explanation = "$label — a gentle -$penalty XP. Tomorrow is a fresh start.",
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
     * Bonus for logging past a measured quest's target (fraction > 1.0). Rises
     * with the overshoot but is capped and diminishing — going 3× the target isn't
     * 3× the reward — so over-completion can't be farmed (SPEC §8). Returns 0 for
     * any normal completion (fraction ≤ 1.0).
     */
    internal fun overCompletionBonus(fraction: Double): Double {
        val overshoot = (fraction - 1.0).coerceAtLeast(0.0)
        if (overshoot <= 0.0) return 0.0
        val half = config.overCompletionHalfTargets.coerceAtLeast(1e-6)
        return config.overCompletionMaxBonus * (overshoot / (overshoot + half))
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
        multipliers["overCompletion"]?.let { parts += "over-completion ×$it (bonus for going past the target)" }
        if ((multipliers["antiFarm"] ?: 1.0) < 1.0) parts += "repeat ×${multipliers["antiFarm"]} (diminishing returns)"
        capReason?.let { parts += it }
        return parts.joinToString("; ") + "."
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
