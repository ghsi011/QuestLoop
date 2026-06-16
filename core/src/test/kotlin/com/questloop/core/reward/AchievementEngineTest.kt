package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AchievementEngineTest {

    private fun rec(
        id: String,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        result: CompletionResult = CompletionResult.COMPLETED,
        day: Long = 1,
    ) = CompletionRecord(
        instanceId = "$id@$day",
        questId = id,
        category = category,
        difficulty = Difficulty.MEDIUM,
        priority = Priority.NORMAL,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        isMeta = category.isMeta,
    )

    @Test
    fun `first steps unlocks on first completion`() {
        val stats = ProgressStats.from(listOf(rec("q1")), totalXp = 20, longestStreak = 1)
        val unlocked = AchievementEngine.unlocked(stats).map { it.id }
        assertTrue("first_steps" in unlocked)
        assertFalse("getting_going" in unlocked)
    }

    @Test
    fun `streak achievements gate on longest streak`() {
        val stats = ProgressStats.from(listOf(rec("q1")), totalXp = 20, longestStreak = 7)
        val unlocked = AchievementEngine.unlocked(stats).map { it.id }
        assertTrue("consistent" in unlocked)
        assertTrue("week_warrior" in unlocked)
    }

    @Test
    fun `well rounded needs five categories`() {
        val cats = QuestCategory.entries.take(5)
        val records = cats.mapIndexed { i, c -> rec("q$i", category = c) }
        val stats = ProgressStats.from(records, totalXp = 100, longestStreak = 1)
        assertTrue("well_rounded" in AchievementEngine.unlocked(stats).map { it.id })
    }

    @Test
    fun `honesty logs count failed reduction quests`() {
        val records = (1..5).map {
            rec("h$it", category = QuestCategory.BAD_HABIT_REDUCTION, result = CompletionResult.FAILED)
        }
        val stats = ProgressStats.from(records, totalXp = 0, longestStreak = 0)
        assertEquals(5, stats.honestyLogs)
        assertTrue("honest_tracker" in AchievementEngine.unlocked(stats).map { it.id })
    }

    @Test
    fun `newly unlocked computes the delta`() {
        val before = ProgressStats.from(listOf(rec("q1")), totalXp = 20, longestStreak = 2)
        val after = ProgressStats.from(
            listOf(rec("q1"), rec("q2", day = 2), rec("q3", day = 3)),
            totalXp = 60,
            longestStreak = 3,
        )
        val newly = AchievementEngine.newlyUnlocked(before, after).map { it.id }
        assertTrue("consistent" in newly)
        assertFalse("first_steps" in newly) // already had it
    }

    @Test
    fun `thresholds unlock exactly at the boundary, not one below`() {
        fun ids(stats: ProgressStats) = AchievementEngine.unlocked(stats).map { it.id }.toSet()
        val base = ProgressStats(0, 1, 0, 0, 0, 0)
        assertFalse("getting_going" in ids(base.copy(totalCompleted = 9)))
        assertTrue("getting_going" in ids(base.copy(totalCompleted = 10)))
        assertFalse("centurion" in ids(base.copy(totalCompleted = 99)))
        assertTrue("centurion" in ids(base.copy(totalCompleted = 100)))
        assertFalse("consistent" in ids(base.copy(longestStreak = 2)))
        assertTrue("consistent" in ids(base.copy(longestStreak = 3)))
        assertFalse("week_warrior" in ids(base.copy(longestStreak = 6)))
        assertTrue("week_warrior" in ids(base.copy(longestStreak = 7)))
        assertFalse("level_10" in ids(base.copy(level = 9)))
        assertTrue("level_10" in ids(base.copy(level = 10)))
        assertFalse("well_rounded" in ids(base.copy(distinctCategories = 4)))
        assertTrue("well_rounded" in ids(base.copy(distinctCategories = 5)))
        assertFalse("breaking_free" in ids(base.copy(reductionWins = 9)))
        assertTrue("breaking_free" in ids(base.copy(reductionWins = 10)))
    }

    @Test
    fun `level gates use the xp curve`() {
        val statsLow = ProgressStats.from(listOf(rec("q1")), totalXp = 0, longestStreak = 0)
        assertFalse("level_5" in AchievementEngine.unlocked(statsLow).map { it.id })
        val xpForLevel5 = LevelSystem.xpForLevel(5)
        val statsHigh = ProgressStats.from(listOf(rec("q1")), totalXp = xpForLevel5, longestStreak = 0)
        assertTrue("level_5" in AchievementEngine.unlocked(statsHigh).map { it.id })
    }
}
