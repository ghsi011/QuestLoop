package com.questloop.core.generation

import com.questloop.core.completion.CompletionSlots
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek

/**
 * Builds a forward-looking **weekly or monthly plan** from the candidate pool
 * (SPEC §4: dedicated weekly/monthly quest lists, distinct from the single daily
 * plan). Where [QuestGenerator] answers "what should I do *today* within my
 * energy/time budget?", this answers "what's on my plate across this *period*?" —
 * the recurring backbone (with how many times each cadence is expected to come
 * due), plus the dated one-off work that has to land before the period ends.
 *
 * Pure and platform-agnostic: the caller passes the inclusive day window
 * `[fromEpochDay, toEpochDay]` (computed from the calendar in the app layer, the
 * same boundaries the weekly/monthly review uses) and a map of each quest's last
 * completion day. Cadence math is delegated to [QuestScheduler], so a quest's
 * "expected occurrences" stay consistent with what actually gets scheduled day to
 * day — except measured weekly/monthly quests, which follow [CompletionSlots]'
 * calendar intervals (the same boundaries that reset them on the Today screen).
 */
class PeriodPlanner {

    data class PlanItem(
        val quest: Quest,
        /** How many times this quest is expected to come due across the window. */
        val expectedOccurrences: Int,
        /** First day in the window the quest is due, or null if purely deadline-driven. */
        val firstDueEpochDay: Long?,
        val deadlineEpochDay: Long?,
        /** Deadline fell before the window started — it's already late. */
        val isOverdue: Boolean,
        /** Deadline lands inside this window — it must be done this period. */
        val dueThisPeriod: Boolean,
    ) {
        /** Optimistic time this quest implies over the period (per-occurrence × count). */
        val estimatedMinutes: Int get() = quest.estimatedMinutes * expectedOccurrences
    }

    data class CategoryGroup(
        val category: QuestCategory,
        val items: List<PlanItem>,
        val estimatedMinutes: Int,
    )

    data class PeriodPlan(
        val periodLabel: String,
        val fromEpochDay: Long,
        val toEpochDay: Long,
        /** All scheduled items, ordered most-pressing first. */
        val items: List<PlanItem>,
        /** The same items grouped by category, in category order, with subtotals. */
        val byCategory: List<CategoryGroup>,
        val totalEstimatedMinutes: Int,
        val notes: List<String>,
    )

    /**
     * @param periodLabel human-readable label, e.g. "This week" / "This month".
     * @param fromEpochDay inclusive start of the window (epoch day).
     * @param toEpochDay inclusive end of the window (epoch day).
     * @param candidates the active quest pool (real quests + derived habit/goal quests).
     * @param lastCompletedByQuest most recent completion day per quest id; absent = never.
     * @param firstDayOfWeek the user's week start (default Sunday), for weekly interval math.
     */
    fun plan(
        periodLabel: String,
        fromEpochDay: Long,
        toEpochDay: Long,
        candidates: List<Quest>,
        lastCompletedByQuest: Map<String, Long> = emptyMap(),
        firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    ): PeriodPlan {
        val items = candidates.mapNotNull { quest ->
            val lastCompleted = lastCompletedByQuest[quest.id]
            // A measured weekly/monthly quest accumulates toward its target across
            // a calendar interval and resets at the boundary — the same slot math
            // that keys its completion records — so its due days come from the
            // calendar, not from the rolling last-completion window used for
            // everything else. Otherwise this plan would contradict the Today
            // screen (e.g. a monthly quest finished late last month would vanish
            // from this month's plan even though Today resurfaces it from the 1st).
            val interval = CompletionSlots.hasCalendarInterval(quest)
            val (occurrences, firstDue) = if (interval) {
                intervalSchedule(quest.frequency, fromEpochDay, toEpochDay, lastCompleted, firstDayOfWeek)
            } else {
                QuestScheduler.expectedOccurrences(quest.frequency, fromEpochDay, toEpochDay, lastCompleted) to
                    QuestScheduler.firstDueDay(quest.frequency, fromEpochDay, toEpochDay, lastCompleted)
            }
            val deadline = quest.deadlineEpochDay
            // A deadline only pulls a quest in while it's still unmet. A completed
            // one-off (or a recurring quest already satisfied for its period) must
            // not linger in the plan as "Due …"/"Overdue" forever.
            val unmet = if (interval) {
                intervalDue(quest.frequency, toEpochDay, lastCompleted, firstDayOfWeek)
            } else {
                QuestScheduler.isDue(quest.frequency, toEpochDay, lastCompleted)
            }
            val dueThisPeriod = unmet && deadline != null && deadline in fromEpochDay..toEpochDay
            val isOverdue = unmet && deadline != null && deadline < fromEpochDay
            // Drop quests with no occurrences this period unless an unmet deadline
            // (overdue or due-this-period) genuinely keeps them on the plate.
            if (occurrences == 0 && !dueThisPeriod && !isOverdue) return@mapNotNull null
            PlanItem(
                quest = quest,
                expectedOccurrences = occurrences,
                firstDueEpochDay = firstDue,
                deadlineEpochDay = deadline,
                isOverdue = isOverdue,
                dueThisPeriod = dueThisPeriod,
            )
        }.sortedWith(itemOrder)

        val byCategory = items
            .groupBy { it.quest.category }
            .map { (category, group) ->
                CategoryGroup(category, group, group.sumOf { it.estimatedMinutes })
            }
            .sortedBy { it.category.ordinal }

        val totalMinutes = items.sumOf { it.estimatedMinutes }
        return PeriodPlan(
            periodLabel = periodLabel,
            fromEpochDay = fromEpochDay,
            toEpochDay = toEpochDay,
            items = items,
            byCategory = byCategory,
            totalEstimatedMinutes = totalMinutes,
            notes = buildNotes(periodLabel, items, totalMinutes),
        )
    }

    /**
     * Interval counterpart of [QuestScheduler.expectedOccurrences] +
     * [QuestScheduler.firstDueDay] for measured weekly/monthly quests: walks the
     * calendar intervals overlapping `[from, to]` and counts each one still
     * needing work. The interval holding the last completion is already satisfied
     * (its record accumulates until the boundary); every other interval expects
     * the quest once. Returns `(occurrences, firstDueEpochDay)`.
     */
    private fun intervalSchedule(
        frequency: QuestFrequency,
        from: Long,
        to: Long,
        lastCompleted: Long?,
        firstDayOfWeek: DayOfWeek,
    ): Pair<Int, Long?> {
        if (from > to) return 0 to null
        val satisfied = lastCompleted?.let { CompletionSlots.intervalStartFor(frequency, it, firstDayOfWeek) }
        var occurrences = 0
        var firstDue: Long? = null
        var start = CompletionSlots.intervalStartFor(frequency, from, firstDayOfWeek)
        while (start <= to) {
            if (start != satisfied) {
                occurrences++
                if (firstDue == null) firstDue = maxOf(from, start)
            }
            start = CompletionSlots.nextIntervalStart(frequency, start, firstDayOfWeek)
        }
        return occurrences to firstDue
    }

    /** Interval counterpart of [QuestScheduler.isDue]: due on [day] unless the
     *  last completion falls in the same calendar interval. */
    private fun intervalDue(
        frequency: QuestFrequency,
        day: Long,
        lastCompleted: Long?,
        firstDayOfWeek: DayOfWeek,
    ): Boolean =
        lastCompleted == null ||
            CompletionSlots.intervalStartFor(frequency, lastCompleted, firstDayOfWeek) !=
            CompletionSlots.intervalStartFor(frequency, day, firstDayOfWeek)

    /**
     * Most-pressing first: overdue, then due-this-period (earliest deadline
     * first), then the recurring/undated backbone by priority then title — so the
     * list reads as "deal with these, then keep these habits up".
     */
    private val itemOrder: Comparator<PlanItem> = compareBy(
        { pressureRank(it) },
        { it.deadlineEpochDay ?: Long.MAX_VALUE },
        { -it.quest.priority.multiplier },
        { it.quest.title.lowercase() },
    )

    private fun pressureRank(item: PlanItem): Int = when {
        item.isOverdue -> 0
        item.dueThisPeriod -> 1
        else -> 2
    }

    private fun buildNotes(
        periodLabel: String,
        items: List<PlanItem>,
        totalMinutes: Int,
    ): List<String> {
        val notes = mutableListOf<String>()
        if (items.isEmpty()) {
            notes += "Nothing scheduled for ${periodLabel.lowercase()} yet — a clear runway."
            return notes
        }
        val overdue = items.count { it.isOverdue }
        if (overdue > 0) {
            notes += "$overdue quest(s) past their deadline — worth clearing first, no shame attached."
        }
        val dueThisPeriod = items.count { it.dueThisPeriod }
        if (dueThisPeriod > 0) {
            notes += "$dueThisPeriod dated quest(s) land in this window."
        }
        val recurring = items.count { it.deadlineEpochDay == null }
        if (recurring > 0) {
            notes += "$recurring recurring quest(s) make up your backbone."
        }
        if (totalMinutes >= 60) {
            val hours = totalMinutes / 60.0
            notes += "Roughly ${"%.1f".format(hours)}h of effort if you do it all — pace yourself."
        }
        return notes
    }
}
