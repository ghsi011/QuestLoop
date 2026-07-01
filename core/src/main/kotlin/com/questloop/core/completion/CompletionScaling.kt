package com.questloop.core.completion

import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle

/**
 * Converts non-binary completion inputs into the `(result, fraction)` pair the
 * reward engine already credits proportionally (SPEC §8: partial completion and
 * subjective tasks).
 *
 * The key fairness rule: progress is never punished. Logging 6 of 8 glasses, or
 * 20 of 50 focus minutes, earns proportional XP — it is recorded as PARTIAL, not
 * FAILED, so it carries no penalty and still moves the streak/consistency along.
 */
object CompletionScaling {

    data class Scaled(val result: CompletionResult, val fraction: Double)

    /** Upper bound on the stored fraction when over-completion is allowed, so a
     *  fat-fingered "999 glasses" can't store an absurd ratio (the reward bonus is
     *  separately capped; this just bounds the raw value). */
    const val MAX_OVER_FRACTION = 5.0

    /**
     * A count toward a target (e.g. glasses of water). When [allowOver] the ratio
     * may exceed 1.0 (progress past the target), bounded by [MAX_OVER_FRACTION];
     * otherwise it's capped at the target as before.
     */
    fun quantitative(progress: Int, target: Int, allowOver: Boolean = false): Scaled {
        require(target > 0) { "target must be > 0" }
        return classify(scaleFraction(progress.toDouble() / target, allowOver))
    }

    /** Minutes spent toward a target duration; see [quantitative] for [allowOver]. */
    fun duration(actualMinutes: Int, targetMinutes: Int, allowOver: Boolean = false): Scaled {
        require(targetMinutes > 0) { "targetMinutes must be > 0" }
        return classify(scaleFraction(actualMinutes.toDouble() / targetMinutes, allowOver))
    }

    private fun scaleFraction(ratio: Double, allowOver: Boolean): Double =
        if (allowOver) ratio.coerceIn(0.0, MAX_OVER_FRACTION) else ratio.coerceIn(0.0, 1.0)

    /**
     * Self-rated effort/progress for fuzzy goals on a 1..[max] scale. Any honest
     * reflection counts as engagement, so even a low rating earns partial credit
     * rather than nothing — recovery and showing up matter (SPEC §8).
     */
    fun subjective(rating: Int, max: Int = 5): Scaled {
        require(max > 0) { "max must be > 0" }
        val r = rating.coerceIn(0, max)
        val frac = (r.toDouble() / max).coerceIn(0.0, 1.0)
        return classify(frac)
    }

    private fun classify(fraction: Double): Scaled = when {
        // Preserve the actual ratio at/above the target so over-completion (>1.0)
        // survives for display and the reward bonus; a plain finish stays exactly 1.0.
        fraction >= 1.0 -> Scaled(CompletionResult.COMPLETED, fraction)
        fraction <= 0.0 -> Scaled(CompletionResult.PARTIAL, 0.0)
        else -> Scaled(CompletionResult.PARTIAL, fraction)
    }
}

/**
 * Decides whether a quest should disappear from today's list given its latest
 * outcome (SPEC §8). The key nuance: a *partially* logged QUANTITATIVE/DURATION
 * quest stays visible so the user can keep adding progress; everything else
 * (finished, skipped, failed, or a one-shot subjective reflection) is done for
 * the day.
 */
object CompletionPolicy {
    fun dismissedForToday(
        style: CompletionStyle,
        result: CompletionResult,
        allowOverCompletion: Boolean = false,
    ): Boolean = when (result) {
        // A measured quest that allows over-completion stays loggable for the whole
        // interval even once the target is reached (the user can keep adding 3/2,
        // 4/2, …); it only leaves the list when the next interval resets it.
        CompletionResult.COMPLETED ->
            !(allowOverCompletion && (style == CompletionStyle.QUANTITATIVE || style == CompletionStyle.DURATION))
        CompletionResult.SKIPPED, CompletionResult.FAILED -> true
        CompletionResult.RESCHEDULED -> false
        CompletionResult.PARTIAL -> when (style) {
            // Counting/timed quests can still accept more progress this interval.
            CompletionStyle.QUANTITATIVE, CompletionStyle.DURATION -> false
            // A subjective reflection or a binary quest is a one-shot for the day.
            CompletionStyle.SUBJECTIVE, CompletionStyle.BINARY -> true
        }
    }
}
