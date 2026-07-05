package com.questloop.core.completion

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestFrequency
import java.time.LocalDate

/**
 * Calendar-interval math for completion records (SPEC §8). A *measured recurring*
 * quest ("swim 2×/week") accumulates progress across a calendar interval — an ISO
 * week for WEEKLY, a calendar month for MONTHLY — and resets at the boundary; the
 * interval start is the slot in its record's `instanceId` (`questId@slot`), the
 * idempotency key that keeps XP from double-counting. Everything else is keyed by
 * the day itself.
 *
 * Single authority for those boundaries: the repository keys records with
 * [completionSlot], the app clock's week/month filters delegate to
 * [startOfWeek]/[startOfMonth]/[endOfWeek]/[endOfMonth], and the period planner's
 * interval scheduling builds on [intervalStartFor]/[nextIntervalStart] — so the
 * Completed filters, review windows and forward plans can never drift from how
 * slots are keyed. Deterministic: time enters only as epoch days ([LocalDate] is
 * used for pure calendar arithmetic, never for reading the clock).
 */
object CompletionSlots {

    /** Measured (count/duration) quests accumulate progress across their interval. */
    fun accumulates(quest: Quest): Boolean =
        quest.completionStyle == CompletionStyle.QUANTITATIVE ||
            quest.completionStyle == CompletionStyle.DURATION

    /**
     * A *measured recurring* quest whose progress accumulates across a calendar
     * interval (a week for WEEKLY, a month for MONTHLY) and resets at the boundary
     * — e.g. "swim 2×/week". Daily/one-off/recurring measured quests keep their
     * per-day semantics (their "interval" is just the day), so they're excluded.
     * This is the set for which interval dismissal, not the rolling isDue window,
     * governs visibility.
     */
    fun hasCalendarInterval(quest: Quest): Boolean =
        accumulates(quest) &&
            (quest.frequency == QuestFrequency.WEEKLY || quest.frequency == QuestFrequency.MONTHLY)

    /**
     * The slot token that anchors a quest's completion record (`instanceId =
     * questId@slot`). For a *measured recurring* quest it's the start of its current
     * calendar interval (week for WEEKLY, month for MONTHLY), so progress accumulates
     * across the interval — e.g. "swim 2×/week" counts both swims toward one 2/2 —
     * and resets at the boundary. A *measured one-off*'s target is cumulative over the
     * quest's whole lifetime (one unbounded interval), so it gets a single fixed slot
     * (`oneoff`): progress accumulates day to day into one record — each re-log nets
     * the prior grant, so spreading "read 100 pages" over several days can't mint more
     * XP than finishing it — until the target is reached once and the quest retires
     * via lastCompleted. For everything else (and daily quests, where the interval
     * *is* the day) it's the day itself, so behaviour is unchanged.
     */
    fun completionSlot(quest: Quest, epochDay: Long): String = when {
        !accumulates(quest) -> epochDay.toString()
        quest.frequency == QuestFrequency.ONE_OFF -> "oneoff"
        else -> intervalStartFor(quest.frequency, epochDay).toString()
    }

    /** Start of the calendar interval a frequency accumulates over: the ISO week's
     *  Monday for WEEKLY, the 1st for MONTHLY, the day itself otherwise. */
    fun intervalStartFor(frequency: QuestFrequency, epochDay: Long): Long = when (frequency) {
        QuestFrequency.WEEKLY -> startOfWeek(epochDay)
        QuestFrequency.MONTHLY -> startOfMonth(epochDay)
        else -> epochDay
    }

    /** Start of the interval *after* the one containing [epochDay] — the day the
     *  quest's progress next resets (a day-long "interval" for other frequencies). */
    fun nextIntervalStart(frequency: QuestFrequency, epochDay: Long): Long = when (frequency) {
        QuestFrequency.WEEKLY -> startOfWeek(epochDay) + 7
        QuestFrequency.MONTHLY -> LocalDate.ofEpochDay(epochDay).withDayOfMonth(1).plusMonths(1).toEpochDay()
        else -> epochDay + 1
    }

    /** First day (Monday) of the ISO week [epochDay] falls in. */
    fun startOfWeek(epochDay: Long): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        return date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    /** First day of the calendar month [epochDay] falls in. */
    fun startOfMonth(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay).withDayOfMonth(1).toEpochDay()

    /** Last day (inclusive) of the ISO week [epochDay] falls in. */
    fun endOfWeek(epochDay: Long): Long = startOfWeek(epochDay) + 6

    /** Last day (inclusive) of the calendar month [epochDay] falls in. */
    fun endOfMonth(epochDay: Long): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        return date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
    }
}
