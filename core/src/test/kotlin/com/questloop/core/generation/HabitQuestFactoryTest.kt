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
    fun `goal becomes a weekly subjective quest`() {
        val q = HabitQuestFactory.fromGoal(
            com.questloop.core.model.Goal(id = "g1", title = "Run a 10k", category = QuestCategory.HEALTH),
        )
        assertEquals("goal-g1", q.id)
        assertEquals(QuestFrequency.WEEKLY, q.frequency)
        assertEquals(com.questloop.core.model.CompletionStyle.SUBJECTIVE, q.completionStyle)
    }

    @Test
    fun `derive all combines habits, bad habits, and goals`() {
        val derived = HabitQuestFactory.deriveAll(
            habits = listOf(Habit(id = "h", title = "Read", category = QuestCategory.PERSONAL_GROWTH, difficulty = Difficulty.EASY)),
            badHabits = listOf(BadHabit(id = "b", title = "Snacking")),
            goals = listOf(com.questloop.core.model.Goal(id = "g", title = "Learn guitar", category = QuestCategory.PERSONAL_GROWTH)),
        )
        assertEquals(3, derived.size)
        assertEquals(setOf("habit-h", "badhabit-b", "goal-g"), derived.map { it.id }.toSet())
    }

    @Test
    fun `derived ids are namespaced and disjoint from stored quest ids`() {
        // Completion records are keyed by "questId@day"; derived quests are NOT in
        // the quests table, so their ids must never collide with stored ids (which
        // are namespaced "user-"/"bank-") or the ledger would cross-credit XP.
        // Even a habit, bad habit and goal that share the same raw id stay distinct.
        val derived = HabitQuestFactory.deriveAll(
            habits = listOf(Habit(id = "x", title = "Read", category = QuestCategory.PERSONAL_GROWTH)),
            badHabits = listOf(BadHabit(id = "x", title = "Snacking")),
            goals = listOf(com.questloop.core.model.Goal(id = "x", title = "Learn guitar", category = QuestCategory.PERSONAL_GROWTH)),
        )
        val ids = derived.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "derived ids must be unique")
        val reserved = listOf("user-", "bank-")
        assertTrue(
            ids.all { id -> reserved.none { id.startsWith(it) } },
            "derived ids must not collide with the user-/bank- namespaces",
        )
        assertTrue(
            ids.all {
                it.startsWith(HabitQuestFactory.HABIT_PREFIX) ||
                    it.startsWith(HabitQuestFactory.BAD_HABIT_PREFIX) ||
                    it.startsWith(HabitQuestFactory.GOAL_PREFIX)
            },
            "every derived id must carry its origin prefix",
        )
    }
}
