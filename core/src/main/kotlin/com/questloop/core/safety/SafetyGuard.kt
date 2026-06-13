package com.questloop.core.safety

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.QuestCategory

/**
 * Watches behavioural patterns and raises *supportive* signals (SPEC section 9).
 *
 * The guard never blocks the user. It surfaces gentle nudges: rest-day
 * suggestions, over-optimization warnings, overload detection and
 * compassionate handling of rough patches. It is intentionally conservative —
 * false alarms are annoying, so thresholds favour real patterns.
 */
class SafetyGuard(private val config: Config = Config()) {

    data class Config(
        /** Active days in a row after which we suggest considering a rest day. */
        val restSuggestionStreak: Int = 10,
        /** Daily completions above which a day looks like over-optimization. */
        val dailyOverdriveCount: Int = 12,
        /** Share of meta quests above which the user may be gaming the system. */
        val metaShareThreshold: Double = 0.5,
        /** Failed-or-skipped share above which we switch to recovery framing. */
        val roughPatchFailureRate: Double = 0.6,
        /** Window (days) used for rate-based signals. */
        val windowDays: Int = 7,
    )

    enum class Severity { INFO, SUGGESTION, WARNING }

    data class Signal(
        val code: String,
        val severity: Severity,
        val message: String,
    )

    /**
     * @param records recent completion records (any length; window applied inside).
     * @param activeEpochDays days the user completed >=1 quest.
     * @param today day of evaluation.
     */
    fun evaluate(
        records: List<CompletionRecord>,
        activeEpochDays: Set<Long>,
        today: Long,
    ): List<Signal> {
        val signals = mutableListOf<Signal>()
        val windowStart = today - config.windowDays + 1
        val window = records.filter { it.epochDay in windowStart..today }

        consecutiveActiveStreak(activeEpochDays, today).let { streak ->
            if (streak >= config.restSuggestionStreak) {
                signals += Signal(
                    code = "REST_SUGGESTION",
                    severity = Severity.SUGGESTION,
                    message = "You've been active $streak days straight. A planned rest day keeps " +
                        "this sustainable — recovery is part of progress, not a setback.",
                )
            }
        }

        // Over-optimization: an unusually large single-day load.
        val byDay = window.groupBy { it.epochDay }
        val maxDay = byDay.maxByOrNull { it.value.size }
        if (maxDay != null && maxDay.value.size >= config.dailyOverdriveCount) {
            signals += Signal(
                code = "OVERDRIVE",
                severity = Severity.WARNING,
                message = "That's a very heavy day (${maxDay.value.size} quests). Doing more isn't " +
                    "always better — consider trimming tomorrow's list.",
            )
        }

        // Meta-farming: too much progress coming from system-maintenance actions.
        val completed = window.filter {
            it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL
        }
        if (completed.size >= 6) {
            val metaShare = completed.count { it.category == QuestCategory.META_MAINTENANCE }
                .toDouble() / completed.size
            if (metaShare >= config.metaShareThreshold) {
                signals += Signal(
                    code = "META_HEAVY",
                    severity = Severity.INFO,
                    message = "A lot of recent progress is from maintenance tasks. Real-world quests " +
                        "are where the meaningful wins are — try one today.",
                )
            }
        }

        // Rough patch: high miss rate → switch to compassionate, recovery framing.
        val attempted = window.count { it.result != CompletionResult.RESCHEDULED }
        if (attempted >= 5) {
            val failRate = window.count {
                it.result == CompletionResult.FAILED || it.result == CompletionResult.SKIPPED
            }.toDouble() / attempted
            if (failRate >= config.roughPatchFailureRate) {
                signals += Signal(
                    code = "RECOVERY_MODE",
                    severity = Severity.SUGGESTION,
                    message = "Rough stretch lately — that's okay. Let's shrink the list to a couple of " +
                        "small wins. Consistency beats perfection.",
                )
            }
        }

        return signals
    }

    /** Longest run of active days ending at [today] (or the most recent active day). */
    internal fun consecutiveActiveStreak(activeEpochDays: Set<Long>, today: Long): Int {
        if (activeEpochDays.isEmpty()) return 0
        var streak = 0
        var cursor = if (activeEpochDays.contains(today)) today else today - 1
        while (activeEpochDays.contains(cursor)) {
            streak++
            cursor--
        }
        return streak
    }
}
