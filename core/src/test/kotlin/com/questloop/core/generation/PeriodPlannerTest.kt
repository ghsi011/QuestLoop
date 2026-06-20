package com.questloop.core.generation

import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
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
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        priority = priority,
        estimatedMinutes = minutes,
        deadlineEpochDay = deadline,
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
    fun `a one-off already completed is dropped unless its deadline keeps it in view`() {
        // Completed one-off with no deadline -> gone.
        val doneNoDeadline = quest("done", QuestFrequency.ONE_OFF)
        val resultA = plan(listOf(doneNoDeadline), lastCompleted = mapOf("done" to 90L))
        assertTrue(resultA.items.isEmpty())

        // Completed one-off whose deadline still lands this period -> retained as dated.
        val doneDated = quest("doneDated", QuestFrequency.ONE_OFF, deadline = from + 2)
        val resultB = plan(listOf(doneDated), lastCompleted = mapOf("doneDated" to 90L))
        val item = resultB.items.single()
        assertEquals(0, item.expectedOccurrences)
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
