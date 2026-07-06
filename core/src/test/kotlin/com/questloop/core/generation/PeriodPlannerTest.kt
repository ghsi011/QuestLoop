package com.questloop.core.generation

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodPlannerTest {

    private val planner = PeriodPlanner()

    // A 7-day inclusive window: days 100..106.
    private val from = 100L
    private val to = 106L

    private fun quest(
        id: String,
        frequency: QuestFrequency,
        category: QuestCategory = QuestCategory.HEALTH,
        priority: Priority = Priority.NORMAL,
        minutes: Int = 10,
        deadline: Long? = null,
        style: CompletionStyle = CompletionStyle.BINARY,
        target: Int? = null,
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        priority = priority,
        estimatedMinutes = minutes,
        deadlineEpochDay = deadline,
        completionStyle = style,
        targetCount = target,
    )

    private fun plan(candidates: List<Quest>, lastCompleted: Map<String, Long> = emptyMap()) =
        planner.plan("This week", from, to, candidates, lastCompleted)

    @Test
    fun `daily quest is expected once per day and its time scales with the window`() {
        val result = plan(listOf(quest("d", QuestFrequency.DAILY, minutes = 10)))
        val item = result.items.single()
        assertEquals(7, item.expectedOccurrences)
        assertEquals(70, item.estimatedMinutes) // 7 days × 10 min
        assertEquals(70, result.totalEstimatedMinutes)
    }

    @Test
    fun `a recurring quest with a future-dated completion still appears`() {
        // A daily quest whose last completion is stamped after the window (clock
        // skew) must not vanish from the plan.
        val daily = quest("d", QuestFrequency.DAILY, minutes = 10)
        val result = plan(listOf(daily), lastCompleted = mapOf("d" to to + 50))
        val item = result.items.single()
        assertEquals(7, item.expectedOccurrences)
    }

    @Test
    fun `quests not due in the window are dropped`() {
        // Monthly completed mid-window -> not due again this week.
        val result = plan(
            listOf(quest("m", QuestFrequency.MONTHLY)),
            lastCompleted = mapOf("m" to 100L),
        )
        assertTrue(result.items.isEmpty())
        assertTrue(result.notes.any { it.contains("clear runway") })
    }

    @Test
    fun `an overdue dated quest is kept and sorted first`() {
        val overdue = quest("late", QuestFrequency.ONE_OFF, deadline = from - 5)
        val daily = quest("d", QuestFrequency.DAILY)
        val result = plan(listOf(daily, overdue))

        assertEquals("late", result.items.first().quest.id)
        val lateItem = result.items.first { it.quest.id == "late" }
        assertTrue(lateItem.isOverdue)
        assertFalse(lateItem.dueThisPeriod)
    }

    @Test
    fun `a completed one-off is dropped even when it is dated`() {
        // Completed one-off with no deadline -> gone.
        val doneNoDeadline = quest("done", QuestFrequency.ONE_OFF)
        val resultA = plan(listOf(doneNoDeadline), lastCompleted = mapOf("done" to 90L))
        assertTrue(resultA.items.isEmpty())

        // A completed one-off must NOT linger as planned work just because its
        // deadline falls in (or before) the window — it's already done.
        val doneDueThisPeriod = quest("doneDated", QuestFrequency.ONE_OFF, deadline = from + 2)
        assertTrue(plan(listOf(doneDueThisPeriod), lastCompleted = mapOf("doneDated" to 90L)).items.isEmpty())

        val doneOverdue = quest("doneLate", QuestFrequency.ONE_OFF, deadline = from - 3)
        assertTrue(plan(listOf(doneOverdue), lastCompleted = mapOf("doneLate" to 95L)).items.isEmpty())
    }

    @Test
    fun `an unmet dated one-off is still kept`() {
        // Never completed, deadline this period -> retained as dated work.
        val dued = quest("due", QuestFrequency.ONE_OFF, deadline = from + 2)
        val item = plan(listOf(dued)).items.single()
        assertEquals(1, item.expectedOccurrences) // an unmet one-off counts once
        assertTrue(item.dueThisPeriod)
    }

    @Test
    fun `due-this-period quests sort by deadline, before the recurring backbone`() {
        val dueLate = quest("dueLate", QuestFrequency.ONE_OFF, deadline = from + 5)
        val dueSoon = quest("dueSoon", QuestFrequency.ONE_OFF, deadline = from + 1)
        val daily = quest("daily", QuestFrequency.DAILY)
        val result = plan(listOf(daily, dueLate, dueSoon))

        assertEquals(listOf("dueSoon", "dueLate", "daily"), result.items.map { it.quest.id })
    }

    @Test
    fun `items are grouped by category with per-group minute subtotals`() {
        val health = quest("h", QuestFrequency.DAILY, category = QuestCategory.HEALTH, minutes = 10)
        val chores = quest("c", QuestFrequency.WEEKLY, category = QuestCategory.CHORES, minutes = 30)
        val result = plan(listOf(health, chores))

        val healthGroup = result.byCategory.first { it.category == QuestCategory.HEALTH }
        val choresGroup = result.byCategory.first { it.category == QuestCategory.CHORES }
        assertEquals(70, healthGroup.estimatedMinutes) // 7 × 10
        assertEquals(30, choresGroup.estimatedMinutes) // 1 × 30
        // Category order follows the enum ordinal (HEALTH before CHORES).
        assertTrue(
            result.byCategory.indexOfFirst { it.category == QuestCategory.HEALTH } <
                result.byCategory.indexOfFirst { it.category == QuestCategory.CHORES },
        )
    }

    // --- Measured weekly/monthly quests follow calendar intervals, like Today ---

    // A Monday, so calendar-week math is unambiguous (ISO weeks start Monday).
    private val monday = LocalDate.of(2026, 6, 22).toEpochDay()

    private fun weeklySwim() =
        quest("swim", QuestFrequency.WEEKLY, style = CompletionStyle.QUANTITATIVE, target = 2)

    @Test
    fun `a measured weekly quest finished midweek returns at the calendar boundary, like Today`() {
        val wednesday = monday + 2
        val done = mapOf("swim" to wednesday)

        // Rest of this week: the interval is satisfied — Today hides the quest,
        // and so does the plan. (Monday-start weeks pinned for unambiguous math.)
        val mon = DayOfWeek.MONDAY
        val thisWeek = planner.plan("This week", wednesday, monday + 6, listOf(weeklySwim()), done, mon)
        assertTrue(thisWeek.items.isEmpty())

        // Next calendar week: due again from Monday (when Today resurfaces it),
        // not from the rolling wednesday+7.
        val nextWeek = planner.plan("This week", monday + 7, monday + 13, listOf(weeklySwim()), done, mon)
        val item = nextWeek.items.single()
        assertEquals(1, item.expectedOccurrences)
        assertEquals(monday + 7, item.firstDueEpochDay)
    }

    @Test
    fun `a measured weekly quest follows the configured Sunday-start week`() {
        // Default first-day is Sunday: the week containing Monday 6/22 starts Sun 6/21.
        val sunday = monday - 1
        val done = mapOf("swim" to monday) // logged Monday, within the Sun-start week
        // Rest of that week (through Saturday 6/27): interval satisfied → nothing planned.
        val thisWeek = planner.plan("This week", monday, sunday + 6, listOf(weeklySwim()), done)
        assertTrue(thisWeek.items.isEmpty())
        // The next Sunday opens a fresh interval: due again from that Sunday.
        val nextWeek = planner.plan("This week", sunday + 7, sunday + 13, listOf(weeklySwim()), done)
        val item = nextWeek.items.single()
        assertEquals(1, item.expectedOccurrences)
        assertEquals(sunday + 7, item.firstDueEpochDay)
    }

    @Test
    fun `a binary weekly quest keeps the rolling last-completion window`() {
        val wednesday = monday + 2
        val chores = quest("c", QuestFrequency.WEEKLY, category = QuestCategory.CHORES)
        val nextWeek = planner.plan("This week", monday + 7, monday + 13, listOf(chores), mapOf("c" to wednesday))
        // Rolling: next due a full week after the completion, not the calendar Monday.
        assertEquals(wednesday + 7, nextWeek.items.single().firstDueEpochDay)
    }

    @Test
    fun `a measured monthly quest completed late last month is planned from the 1st`() {
        // Completed 30 Jan; February is a fresh interval, so Today surfaces the
        // quest from the 1st. The rolling 30-day window would put the next due at
        // 1 Mar — past February entirely — silently dropping it from the plan.
        val jan30 = LocalDate.of(2026, 1, 30).toEpochDay()
        val feb1 = LocalDate.of(2026, 2, 1).toEpochDay()
        val feb28 = LocalDate.of(2026, 2, 28).toEpochDay()
        val budget = quest("b", QuestFrequency.MONTHLY, style = CompletionStyle.DURATION, minutes = 30)

        val plan = planner.plan("This month", feb1, feb28, listOf(budget), mapOf("b" to jan30))
        val item = plan.items.single()
        assertEquals(1, item.expectedOccurrences)
        assertEquals(feb1, item.firstDueEpochDay)
    }

    @Test
    fun `a measured weekly quest counts calendar weeks, not rolling fence-posts`() {
        // Window: Wednesday through the next Monday. Two calendar weeks overlap it
        // and the quest is unmet in both — doable now (from Wednesday) and again
        // once next week resets it — where rolling math would count just one.
        val wednesday = monday + 2
        val plan = planner.plan("This week", wednesday, monday + 7, listOf(weeklySwim()), firstDayOfWeek = DayOfWeek.MONDAY)
        val item = plan.items.single()
        assertEquals(2, item.expectedOccurrences)
        assertEquals(wednesday, item.firstDueEpochDay)
    }

    @Test
    fun `an inverted window plans nothing for a measured quest`() {
        val plan = planner.plan("This week", monday, monday - 1, listOf(weeklySwim()))
        assertTrue(plan.items.isEmpty())
    }

    @Test
    fun `notes summarise overdue, dated, recurring and total effort`() {
        val overdue = quest("o", QuestFrequency.ONE_OFF, deadline = from - 1)
        val dated = quest("t", QuestFrequency.ONE_OFF, deadline = from + 2)
        val daily = quest("d", QuestFrequency.DAILY, minutes = 30)
        val result = plan(listOf(overdue, dated, daily))

        assertTrue(result.notes.any { it.contains("past their deadline") })
        assertTrue(result.notes.any { it.contains("dated quest") })
        assertTrue(result.notes.any { it.contains("backbone") })
        assertTrue(result.notes.any { it.contains("h of effort") })
    }
}
