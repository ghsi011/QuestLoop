package com.questloop.core.generation

import com.questloop.core.model.BadHabit
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitQuestFactoryTest {

    @Test
    fun `frequent habit becomes a daily quest`() {
        val q = HabitQuestFactory.fromHabit(
            Habit(id = "h1", title = "Stretch", category = QuestCategory.HEALTH, targetPerWeek = 7),
        )
        assertEquals("habit-h1", q.id)
        assertEquals(QuestFrequency.DAILY, q.frequency)
        assertEquals(QuestCategory.HEALTH, q.category)
    }

    @Test
    fun `occasional habit becomes a weekly quest`() {
        val q = HabitQuestFactory.fromHabit(
            Habit(id = "h2", title = "Deep clean", category = QuestCategory.CHORES, targetPerWeek = 2),
        )
        assertEquals(QuestFrequency.WEEKLY, q.frequency)
    }

    @Test
    fun `bad habit becomes a daily reduction quest`() {
        val q = HabitQuestFactory.fromBadHabit(BadHabit(id = "b1", title = "Doomscrolling", dailyLimit = 30))
        assertEquals("badhabit-b1", q.id)
        assertEquals(QuestCategory.BAD_HABIT_REDUCTION, q.category)
        assertTrue(q.isReductionQuest)
        assertTrue(q.rationale!!.contains("30"))
    }

    @Test
    fun `derive all combines habits and bad habits`() {
        val derived = HabitQuestFactory.deriveAll(
            habits = listOf(Habit(id = "h", title = "Read", category = QuestCategory.PERSONAL_GROWTH, difficulty = Difficulty.EASY)),
            badHabits = listOf(BadHabit(id = "b", title = "Snacking")),
        )
        assertEquals(2, derived.size)
        assertEquals(setOf("habit-h", "badhabit-b"), derived.map { it.id }.toSet())
    }
}
