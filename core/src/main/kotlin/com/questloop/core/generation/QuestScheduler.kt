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

    /**
     * The first day within the inclusive window `[from, to]` on which the quest
     * becomes due, or null if it isn't due at any point in the window. Powers the
     * weekly/monthly planner so a quest can be slotted against the day it's next
     * expected rather than just "today".
     *
     * One-off and seasonal quests have no cadence, so they report `from` when
     * still eligible (the planner overlays any explicit deadline on top).
     */
    fun firstDueDay(
        frequency: QuestFrequency,
        from: Long,
        to: Long,
        lastCompletedEpochDay: Long?,
    ): Long? {
        if (from > to) return null
        return when (frequency) {
            QuestFrequency.DAILY, QuestFrequency.RECURRING ->
                firstPeriodicDue(from, to, lastCompletedEpochDay, 1L)
            QuestFrequency.WEEKLY ->
                firstPeriodicDue(from, to, lastCompletedEpochDay, WEEKLY_PERIOD_DAYS)
            QuestFrequency.MONTHLY ->
                firstPeriodicDue(from, to, lastCompletedEpochDay, MONTHLY_PERIOD_DAYS)
            QuestFrequency.ONE_OFF -> if (lastCompletedEpochDay == null) from else null
            QuestFrequency.SEASONAL -> from
        }
    }

    /**
     * How many times the quest is expected to come due within the inclusive
     * window `[from, to]`, assuming it's completed each time it's due (the
     * optimistic planning view). Recurring cadences are counted at their period
     * spacing; one-off/seasonal quests count once if still eligible. Used to size
     * a period plan (e.g. a daily quest is "~7×" across a week) and to estimate
     * the time it implies.
     */
    fun expectedOccurrences(
        frequency: QuestFrequency,
        from: Long,
        to: Long,
        lastCompletedEpochDay: Long?,
    ): Int {
        if (from > to) return 0
        return when (frequency) {
            QuestFrequency.DAILY, QuestFrequency.RECURRING ->
                periodicCount(from, to, lastCompletedEpochDay, 1L)
            QuestFrequency.WEEKLY ->
                periodicCount(from, to, lastCompletedEpochDay, WEEKLY_PERIOD_DAYS)
            QuestFrequency.MONTHLY -> {
                val count = periodicCount(from, to, lastCompletedEpochDay, MONTHLY_PERIOD_DAYS)
                // A calendar month can be 31 days — longer than the 30-day rolling
                // period — so the fence-post count would report a once-a-month quest
                // as twice on the 1st of a long month. Cap by the number of whole
                // calendar months the window can hold (using 31 as the max month).
                val spanDays = to - from + 1
                val maxMonths = ((spanDays + 30) / 31).toInt().coerceAtLeast(1)
                minOf(count, maxMonths)
            }
            QuestFrequency.ONE_OFF -> if (lastCompletedEpochDay == null) 1 else 0
            QuestFrequency.SEASONAL -> 1
        }
    }

    /** First due day at `period`-day spacing: `from`, or one full period past the
     *  last completion, whichever is later. Null if that lands beyond the window. */
    private fun firstPeriodicDue(from: Long, to: Long, lastCompleted: Long?, period: Long): Long? {
        val firstDue = if (lastCompleted == null) from else maxOf(from, lastCompleted + period)
        return if (firstDue > to) null else firstDue
    }

    private fun periodicCount(from: Long, to: Long, lastCompleted: Long?, period: Long): Int {
        val firstDue = firstPeriodicDue(from, to, lastCompleted, period) ?: return 0
        return ((to - firstDue) / period).toInt() + 1
    }
}
