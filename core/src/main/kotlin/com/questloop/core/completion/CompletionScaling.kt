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

    /** A count toward a target (e.g. glasses of water). */
    fun quantitative(progress: Int, target: Int): Scaled {
        require(target > 0) { "target must be > 0" }
        val frac = (progress.toDouble() / target).coerceIn(0.0, 1.0)
        return classify(frac)
    }

    /** Minutes spent toward a target duration. */
    fun duration(actualMinutes: Int, targetMinutes: Int): Scaled {
        require(targetMinutes > 0) { "targetMinutes must be > 0" }
        val frac = (actualMinutes.toDouble() / targetMinutes).coerceIn(0.0, 1.0)
        return classify(frac)
    }

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
        fraction >= 1.0 -> Scaled(CompletionResult.COMPLETED, 1.0)
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
    fun dismissedForToday(style: CompletionStyle, result: CompletionResult): Boolean = when (result) {
        CompletionResult.COMPLETED, CompletionResult.SKIPPED, CompletionResult.FAILED -> true
        CompletionResult.RESCHEDULED -> false
        CompletionResult.PARTIAL -> when (style) {
            // Counting/timed quests can still accept more progress today.
            CompletionStyle.QUANTITATIVE, CompletionStyle.DURATION -> false
            // A subjective reflection or a binary quest is a one-shot for the day.
            CompletionStyle.SUBJECTIVE, CompletionStyle.BINARY -> true
        }
    }
}
