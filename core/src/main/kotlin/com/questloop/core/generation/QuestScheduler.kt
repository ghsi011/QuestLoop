package com.questloop.core.generation

import com.questloop.core.model.QuestFrequency

/**
 * Decides whether a recurring quest is *due* on a given day based on its
 * frequency and when it was last completed (SPEC §4: daily/weekly/monthly
 * cadence). This is what stops a weekly quest from reappearing every day.
 *
 * Pure and platform-agnostic: cadence is expressed in epoch-day deltas (no
 * `java.time`), so the logic stays KMP-portable and trivially testable. Calendar
 * alignment (e.g. "resets on the 1st") is intentionally out of scope for the MVP
 * — a rolling window is simpler and predictable.
 */
object QuestScheduler {

    /** Days within which a completion satisfies each recurring frequency. */
    const val WEEKLY_PERIOD_DAYS = 7L
    const val MONTHLY_PERIOD_DAYS = 30L

    /**
     * @param frequency the quest's cadence.
     * @param today the day being planned (epoch day).
     * @param lastCompletedEpochDay the most recent day the quest was *completed*,
     *   or null if never completed.
     * @return true if the quest should be eligible to appear today.
     */
    fun isDue(frequency: QuestFrequency, today: Long, lastCompletedEpochDay: Long?): Boolean =
        when (frequency) {
            // Repeatable every day: eligible unless already completed today.
            QuestFrequency.DAILY,
            QuestFrequency.RECURRING,
            -> lastCompletedEpochDay != today

            QuestFrequency.WEEKLY -> isPastPeriod(today, lastCompletedEpochDay, WEEKLY_PERIOD_DAYS)
            QuestFrequency.MONTHLY -> isPastPeriod(today, lastCompletedEpochDay, MONTHLY_PERIOD_DAYS)

            // Done once, then gone forever.
            QuestFrequency.ONE_OFF -> lastCompletedEpochDay == null

            // No cadence gating in the MVP; always eligible.
            QuestFrequency.SEASONAL -> true
        }

    private fun isPastPeriod(today: Long, lastCompleted: Long?, periodDays: Long): Boolean =
        lastCompleted == null || (today - lastCompleted) >= periodDays
}
