package com.questloop.core.generation

import com.questloop.core.completion.CompletionSlots
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Quest-level scheduling on top of [QuestScheduler]'s frequency cadence (SPEC §4):
 * set times of day, a weekly/monthly anchor day ("rent on the 1st"), and a total
 * occurrence limit ("medicine for 5 days") after which the quest retires.
 *
 * Pure and deterministic like the rest of `:core`: days are epoch days, times are
 * minutes since local midnight, and the only wall-clock conversion —
 * [nextTriggerMillis], for the app's reminder alarms — takes `now` and the zone
 * as parameters.
 *
 * Semantics:
 * - **Anchors** apply to *non-accumulating* WEEKLY/MONTHLY quests: the quest
 *   becomes due on the anchor day of each calendar interval (week per the user's
 *   first-day-of-week; calendar month) and stays due until completed within that
 *   interval — a missed rent day keeps nagging until paid, then rests until next
 *   month. Measured (count/duration) weekly/monthly quests keep their existing
 *   interval-accumulation visibility ([CompletionSlots.hasCalendarInterval]);
 *   an anchor on them only shapes reminders, never dueness.
 * - **Occurrence limits** count COMPLETED ledger records (one per interval slot,
 *   since records are keyed `questId@slot`). Partial/skipped/failed intervals
 *   never advance the count, so a missed day extends the run instead of silently
 *   consuming it.
 */
object QuestSchedule {

    /** Upper bound on scheduled times per quest — keeps the alarm fan-out (and the
     *  per-quest PendingIntent space the scheduler must cancel) small and fixed. */
    const val MAX_TIMES_PER_DAY = 6

    private const val MINUTES_RANGE_MAX = 24 * 60 - 1

    /** The synthetic unit stamped on a multi-time binary quest converted to a
     *  per-slot count — also the marker for recognizing such quests on re-edit. */
    const val SLOT_UNIT = "times"

    /** Frequencies a schedule (times / anchor / occurrence limit) can apply to. */
    val schedulableFrequencies: Set<QuestFrequency> = setOf(
        QuestFrequency.DAILY,
        QuestFrequency.WEEKLY,
        QuestFrequency.MONTHLY,
        QuestFrequency.RECURRING,
    )

    /** True once a limited quest has all its occurrences completed — it then
     *  disappears from plans/backlogs/widget like a finished one-off. */
    fun isRetired(quest: Quest, completedOccurrences: Int): Boolean {
        val total = quest.totalOccurrences ?: return false
        return completedOccurrences >= total
    }

    /**
     * Completed occurrences from the days of the quest's COMPLETED ledger records:
     * the number of *distinct calendar intervals* those days fall in (day for
     * daily, week/month for weekly/monthly). A non-measured weekly/monthly quest
     * keys its records per day, so two completions inside one week/month are one
     * occurrence, not two — a raw record count would retire "rent for 12 months"
     * months early. Measured interval quests hold one record per interval, so the
     * distinct-interval count equals the record count for them.
     *
     * The count spans the quest's whole history: adding a limit to a quest with
     * past completions counts those too (the editor copy says so; the quest then
     * lands in the backlog's Finished group rather than vanishing). A
     * [firstDayOfWeek] change re-buckets weekly history — the same documented
     * cost as the preference's other week-anchored behaviours.
     */
    fun completedOccurrences(
        quest: Quest,
        completedEpochDays: Collection<Long>,
        firstDayOfWeek: DayOfWeek,
    ): Int = completedEpochDays
        .mapTo(mutableSetOf()) { CompletionSlots.intervalStartFor(quest.frequency, it, firstDayOfWeek) }
        .size

    /** Whether an anchor day governs this quest's dueness (see class doc: measured
     *  interval quests accumulate instead, so their anchor never gates dueness). */
    fun hasDueAnchor(quest: Quest): Boolean =
        !CompletionSlots.hasCalendarInterval(quest) &&
            ((quest.frequency == QuestFrequency.WEEKLY && quest.scheduledDayOfWeek != null) ||
                (quest.frequency == QuestFrequency.MONTHLY && quest.scheduledDayOfMonth != null))

    /**
     * The anchor day within the calendar interval containing [epochDay], or null
     * when the quest has no anchor for its frequency. A monthly day-of-month is
     * clamped to the month's length (the 31st anchors to Feb 28/29).
     */
    fun anchorDayIn(quest: Quest, epochDay: Long, firstDayOfWeek: DayOfWeek): Long? = when {
        quest.frequency == QuestFrequency.WEEKLY && quest.scheduledDayOfWeek != null -> {
            val start = CompletionSlots.startOfWeek(epochDay, firstDayOfWeek)
            val startDow = LocalDate.ofEpochDay(start).dayOfWeek
            start + ((quest.scheduledDayOfWeek.value - startDow.value + 7) % 7)
        }
        quest.frequency == QuestFrequency.MONTHLY && quest.scheduledDayOfMonth != null -> {
            val date = LocalDate.ofEpochDay(epochDay)
            date.withDayOfMonth(quest.scheduledDayOfMonth.coerceIn(1, date.lengthOfMonth())).toEpochDay()
        }
        else -> null
    }

    /**
     * Quest-aware dueness: retirement first, then the anchor-day calendar gate for
     * anchored weekly/monthly quests, else the plain [QuestScheduler] cadence.
     *
     * Callers that gate measured interval quests by dismissal (the plan/backlog
     * paths) should keep doing so — for those quests this simply defers to the
     * rolling cadence exactly as [QuestScheduler.isDue] did before.
     */
    fun isDue(
        quest: Quest,
        today: Long,
        lastCompletedEpochDay: Long?,
        completedOccurrences: Int,
        firstDayOfWeek: DayOfWeek,
    ): Boolean {
        if (isRetired(quest, completedOccurrences)) return false
        if (!hasDueAnchor(quest)) {
            return QuestScheduler.isDue(quest.frequency, today, lastCompletedEpochDay)
        }
        val intervalStart = CompletionSlots.intervalStartFor(quest.frequency, today, firstDayOfWeek)
        val intervalEnd = CompletionSlots.nextIntervalStart(quest.frequency, today, firstDayOfWeek) - 1
        // A completion anywhere in the current interval satisfies it — completing
        // early (rent on the 28th for the 1st) must not re-nag on the anchor day.
        val completedThisInterval =
            lastCompletedEpochDay != null && lastCompletedEpochDay in intervalStart..intervalEnd
        if (completedThisInterval) return false
        val anchor = anchorDayIn(quest, today, firstDayOfWeek) ?: return false
        return today >= anchor
    }

    /**
     * Canonicalises a quest's schedule fields before persisting:
     * - times are validated (0..1439), deduped, sorted, and capped at [MAX_TIMES_PER_DAY];
     *   non-recurring frequencies carry no schedule at all; WEEKLY/MONTHLY quests
     *   keep a single time — several times per day would force the multi-slot
     *   count conversion below, which as a measured quest would stop the anchor
     *   day from gating dueness ([hasDueAnchor]) and silently diverge from the
     *   anchor the user just picked;
     * - anchors are kept only on their matching frequency; day-of-month is clamped to 1..31;
     * - a non-positive occurrence limit means "no limit";
     * - reminders require at least one time;
     * - a BINARY quest with several times becomes a QUANTITATIVE count of its time
     *   slots (one loggable unit per time) so "twice a day" is per-slot loggable
     *   without changing how completion records are keyed. An explicitly measured
     *   quest keeps its own target.
     */
    fun normalized(quest: Quest): Quest {
        val schedulable = quest.frequency in schedulableFrequencies
        val singleTimeOnly =
            quest.frequency == QuestFrequency.WEEKLY || quest.frequency == QuestFrequency.MONTHLY
        val times =
            if (schedulable) {
                quest.scheduledTimes.filter { it in 0..MINUTES_RANGE_MAX }
                    .distinct().sorted().take(if (singleTimeOnly) 1 else MAX_TIMES_PER_DAY)
            } else {
                emptyList()
            }
        val multiSlotBinary = times.size > 1 && quest.completionStyle == CompletionStyle.BINARY
        // The inverse direction: a quest previously converted by the multi-slot rule
        // (recognizable by the synthetic unit + a target within the slot cap) whose
        // times were edited must re-derive its target — otherwise dropping from two
        // times to one leaves a 1-of-2 day that can never complete. Down to a single
        // time it converts back to plain BINARY. Only quests still carrying scheduled
        // times are touched, so a genuine unscheduled "5 times" counter is never
        // rewritten (a scheduled counter with the literal unit "times" and a small
        // target is the one accepted ambiguity).
        val slotDerived = quest.completionStyle == CompletionStyle.QUANTITATIVE &&
            quest.unit == SLOT_UNIT && times.isNotEmpty() &&
            (quest.targetCount ?: 0) in 1..MAX_TIMES_PER_DAY
        return quest.copy(
            scheduledTimes = times,
            scheduledDayOfWeek = quest.scheduledDayOfWeek.takeIf { quest.frequency == QuestFrequency.WEEKLY },
            scheduledDayOfMonth = quest.scheduledDayOfMonth?.takeIf { quest.frequency == QuestFrequency.MONTHLY }
                ?.coerceIn(1, 31),
            totalOccurrences = quest.totalOccurrences?.takeIf { schedulable && it > 0 },
            remindersEnabled = quest.remindersEnabled && times.isNotEmpty(),
            completionStyle = when {
                multiSlotBinary -> CompletionStyle.QUANTITATIVE
                slotDerived && times.size <= 1 -> CompletionStyle.BINARY
                else -> quest.completionStyle
            },
            targetCount = when {
                multiSlotBinary || (slotDerived && times.size > 1) -> times.size
                slotDerived && times.size <= 1 -> null
                else -> quest.targetCount
            },
            unit = when {
                multiSlotBinary -> SLOT_UNIT
                slotDerived && times.size <= 1 -> null
                else -> quest.unit
            },
        )
    }

    /**
     * The first day at or after [fromEpochDay] this quest's schedule can land on,
     * ignoring completion state (a fire-time dueness check handles that):
     * - daily/rolling cadences: every day;
     * - *due-anchored* weekly/monthly ([hasDueAnchor]): the anchor day OR any later
     *   day of the same interval — the quest stays *due* through the interval's
     *   tail when missed ("rent unpaid on the 3rd"), so its reminder keeps landing
     *   there too (the fire-time gate silences it once completed);
     * - *measured* anchored quests: the next anchor day only. Their anchor never
     *   gates dueness (they accumulate all interval), so the user's chosen day is
     *   purely "when to nudge me" — tailing would turn a weekly nudge into a
     *   daily nag.
     */
    fun nextScheduledDay(quest: Quest, fromEpochDay: Long, firstDayOfWeek: DayOfWeek): Long {
        val anchorInInterval = anchorDayIn(quest, fromEpochDay, firstDayOfWeek) ?: return fromEpochDay
        return when {
            hasDueAnchor(quest) -> maxOf(fromEpochDay, anchorInInterval)
            anchorInInterval >= fromEpochDay -> anchorInInterval
            quest.frequency == QuestFrequency.WEEKLY -> anchorInInterval + 7
            else -> {
                val nextMonth = LocalDate.ofEpochDay(fromEpochDay).withDayOfMonth(1).plusMonths(1)
                anchorDayIn(quest, nextMonth.toEpochDay(), firstDayOfWeek)!!
            }
        }
    }

    /**
     * Next epoch-millis one of [Quest.scheduledTimes] occurs on a scheduled day,
     * strictly after [nowEpochMillis] — the app's reminder alarm instant. Null when
     * the quest has no valid times. Deterministic given `now` + [zone]; a DST gap
     * resolves forward per [java.time] rules.
     */
    fun nextTriggerMillis(
        quest: Quest,
        nowEpochMillis: Long,
        firstDayOfWeek: DayOfWeek,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val times = quest.scheduledTimes.filter { it in 0..MINUTES_RANGE_MAX }.sorted()
        if (times.isEmpty()) return null
        val now = Instant.ofEpochMilli(nowEpochMillis)
        var day = nextScheduledDay(quest, now.atZone(zone).toLocalDate().toEpochDay(), firstDayOfWeek)
        // A monthly anchor recurs within ~62 days of any start; a couple of spare
        // iterations absorb same-day times already past.
        repeat(64) {
            val date = LocalDate.ofEpochDay(day)
            for (minuteOfDay in times) {
                val candidate = date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant()
                if (candidate.isAfter(now)) return candidate.toEpochMilli()
            }
            day = nextScheduledDay(quest, day + 1, firstDayOfWeek)
        }
        return null
    }
}
