package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Repository tests on an in-memory Room DB (Robolectric) with a fake preferences
 * store. Focuses on the invariants from the code review: idempotent completion,
 * ledger-as-source-of-truth XP, and style-aware dismissal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    private class FakePrefs(cap: Double = 0.0) : ProfilePreferences {
        override val profile: Flow<UserProfile> =
            flowOf(UserProfile(preferences = UserPreferences(monthlyRewardBudgetCap = cap)))
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs())
    }

    @After
    fun tearDown() = db.close()

    private fun quest(
        id: String,
        style: CompletionStyle = CompletionStyle.BINARY,
        target: Int? = null,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        difficulty: Difficulty = Difficulty.MEDIUM,
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = QuestFrequency.DAILY,
        difficulty = difficulty,
        completionStyle = style,
        targetCount = target,
    )

    @Test
    fun `total xp comes from the ledger`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertEquals(20L, repo.totalXp())
    }

    @Test
    fun `re-completing the same quest the same day does not double xp`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val first = repo.totalXp()
        // Complete again (e.g. a double tap) — must be idempotent.
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertEquals(first, repo.totalXp())
    }

    @Test
    fun `completed quest is dismissed from today's plan`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.none { it.quest.id == "a" })
    }

    @Test
    fun `partially logged quantitative quest stays visible and accumulates`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 3) // 3/8 -> PARTIAL

        // Still offered today, with progress remembered.
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.any { it.quest.id == "water" })
        assertEquals(3, repo.todayProgress(1)["water"])

        // Logging more accumulates to completion and then dismisses it.
        repo.completeMeasured(water, epochDay = 1, value = 8)
        val plan2 = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertFalse(plan2.quests.any { it.quest.id == "water" })
    }

    @Test
    fun `measured progress is monotonic - a lower re-log does not reduce it`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 6)
        repo.completeMeasured(water, epochDay = 1, value = 2) // should not lower 6
        assertEquals(6, repo.todayProgress(1)["water"])
    }

    @Test
    fun `failing a reduction quest never reduces xp`() = runTest {
        val smoke = quest("smoke", category = QuestCategory.BAD_HABIT_REDUCTION)
        repo.addQuest(smoke)
        repo.completeQuest(smoke, epochDay = 1, result = CompletionResult.FAILED)
        assertTrue(repo.totalXp() >= 0)
    }

    @Test
    fun `a weekly quest does not reappear the day after completion`() = runTest {
        val weekly = quest("review", category = QuestCategory.META_MAINTENANCE)
            .copy(frequency = QuestFrequency.WEEKLY)
        repo.addQuest(weekly)
        repo.completeQuest(weekly, epochDay = 1, result = CompletionResult.COMPLETED)

        // Next day: not due yet.
        assertFalse(repo.todayPlan(epochDay = 2, dayPart = DayPart.MIDDAY).quests.any { it.quest.id == "review" })
        // A week later: due again.
        assertTrue(repo.todayPlan(epochDay = 8, dayPart = DayPart.MIDDAY).quests.any { it.quest.id == "review" })
    }

    @Test
    fun `first completion unlocks the first-steps achievement`() = runTest {
        repo.addQuest(quest("a"))
        val outcome = repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(outcome.newlyUnlocked.any { it.id == "first_steps" })
    }
}
