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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
 * Covers the measured-completion paths for each [CompletionStyle], streaks across
 * days, achievements/progress-stats, and the review/allowance aggregates — the
 * QuestRepository paths not already exercised by QuestRepositoryTest. Mirrors that
 * test's in-memory Room + fake preferences setup exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryCompletionStylesTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    private class FakePrefs(cap: Double = 0.0) : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile(preferences = UserPreferences(monthlyRewardBudgetCap = cap)))
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(monthlyRewardBudgetCap = value),
            )
        }
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(focusCategories = cats),
            )
        }
        override suspend fun setStreakGraceDays(value: Int) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(streakGraceDays = value),
            )
        }
        override suspend fun setSensitiveOptIn(value: Boolean) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(sensitiveNotificationsOptIn = value),
            )
        }
        override suspend fun setHabits(habits: List<com.questloop.core.model.Habit>) {
            state.value = state.value.copy(habits = habits)
        }
        override suspend fun setBadHabits(badHabits: List<com.questloop.core.model.BadHabit>) {
            state.value = state.value.copy(badHabits = badHabits)
        }
        override suspend fun setGoals(goals: List<com.questloop.core.model.Goal>) {
            state.value = state.value.copy(goals = goals)
        }
        private var checkIn: com.questloop.core.model.EnergyCheckIn? = null
        override suspend fun setCheckIn(checkIn: com.questloop.core.model.EnergyCheckIn?) {
            this.checkIn = checkIn
        }
        override suspend fun getCheckIn(): com.questloop.core.model.EnergyCheckIn? = checkIn
        private var ai = AiConfig()
        override suspend fun getAiConfig(): AiConfig = ai
        override suspend fun setAiConfig(config: AiConfig) { ai = config }
        private var onboarded = false
        override suspend fun isOnboardingComplete(): Boolean = onboarded
        override suspend fun setOnboardingComplete() { onboarded = true }
        private var reminders = ReminderConfig()
        override suspend fun getReminderConfig(): ReminderConfig = reminders
        override suspend fun setReminderConfig(config: ReminderConfig) { reminders = config }
        override suspend fun clear() {
            state.value = UserProfile()
            checkIn = null
            ai = AiConfig()
            onboarded = false
            reminders = ReminderConfig()
        }
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
    fun `quantitative completion at target awards xp and dismisses the quest`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 8)
        assertTrue(repo.totalXp() > 0)
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertFalse(plan.quests.any { it.quest.id == "water" })
    }

    @Test
    fun `duration completion records minutes and credits progress`() = runTest {
        // estimatedMinutes drives the DURATION target; logging the full estimate completes it.
        val focus = quest("focus", style = CompletionStyle.DURATION, category = QuestCategory.WORK_STUDY)
        repo.addQuest(focus)
        repo.completeMeasured(focus, epochDay = 1, value = focus.estimatedMinutes)
        assertTrue(repo.totalXp() > 0)
        // The minutes logged are remembered as today's progress for this quest.
        assertEquals(focus.estimatedMinutes, repo.todayProgress(1)["focus"])
    }

    @Test
    fun `subjective rating awards xp scaled to the score`() = runTest {
        val create = quest("create", style = CompletionStyle.SUBJECTIVE, category = QuestCategory.PERSONAL_GROWTH)
        repo.addQuest(create)
        repo.completeMeasured(create, epochDay = 1, value = 5)
        assertTrue(repo.totalXp() > 0)
    }

    @Test
    fun `binary quest completed through completeMeasured is marked done`() = runTest {
        val b = quest("b", style = CompletionStyle.BINARY)
        repo.addQuest(b)
        repo.completeMeasured(b, epochDay = 1, value = 1)
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertFalse(plan.quests.any { it.quest.id == "b" })
    }

    @Test
    fun `re-logging a measured quest is monotonic and does not stack xp`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 8)
        val first = repo.totalXp()
        // A second, lower log on the same day must neither lower progress nor add XP.
        repo.completeMeasured(water, epochDay = 1, value = 2)
        assertEquals(8, repo.todayProgress(1)["water"])
        assertEquals(first, repo.totalXp())
    }

    @Test
    fun `streak grows across consecutive active days`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        repo.completeQuest(quest("a"), epochDay = 2, result = CompletionResult.COMPLETED)
        repo.completeQuest(quest("a"), epochDay = 3, result = CompletionResult.COMPLETED)
        assertEquals(3, repo.currentStreak(3))
    }

    @Test
    fun `a gap beyond grace resets the streak`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        // No activity on days 2 and 3; by day 4 (default grace) the run is broken.
        assertEquals(0, repo.currentStreak(4))
    }

    @Test
    fun `unlocked achievements grow as milestones are hit`() = runTest {
        assertTrue(repo.unlockedAchievements().isEmpty())
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(repo.unlockedAchievements().any { it.id == "first_steps" })
    }

    @Test
    fun `review aggregates completions for the period`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val review = repo.review("This week", fromEpochDay = 1, toEpochDay = 7)
        assertEquals("This week", review.periodLabel)
    }

    @Test
    fun `allowance reflects the configured budget cap`() = runTest {
        repo.setBudgetCap(50.0)
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val result = repo.allowance(fromEpochDay = 1, toEpochDay = 30)
        assertEquals(50.0, result.budgetCap, 0.0001)
    }

    @Test
    fun `zero-progress partials do not count as active days for the allowance`() = runTest {
        repo.setBudgetCap(30.0)
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 8)
        // "Logged 0 of 8" on two more days: honest and penalty-free, but per the
        // ledger's invariant (CompletionDao.activeDays) NOT real activity — it must
        // not farm the allowance's consistency factor.
        repo.completeMeasured(water, epochDay = 2, value = 0)
        repo.completeMeasured(water, epochDay = 3, value = 0)
        val result = repo.allowance(fromEpochDay = 1, toEpochDay = 30)
        val expected = com.questloop.core.reward.RewardAllowanceCalculator.calculate(
            com.questloop.core.reward.RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = 30.0,
                records = repo.completions.first().filterNot { it.isMeta },
                activeDays = 1, // only day 1 had real progress
                daysInMonth = 30,
            ),
        )
        assertEquals(expected.suggestedAllowance, result.suggestedAllowance, 0.0001)
    }

    @Test
    fun `safety signals query returns no signals on an empty ledger`() = runTest {
        // Nothing logged -> no overtraining/burnout signals to raise.
        assertTrue(repo.safetySignals(today = 30).isEmpty())
    }

    @Test
    fun `goal decomposition falls back to a deterministic step when ai is off`() = runTest {
        val result = repo.decomposeGoal("Learn to paint")
        assertFalse(result.fromAi)
        assertTrue(result.quests.isNotEmpty())
    }

    @Test
    fun `refining a quest reports that ai is off`() = runTest {
        val result = repo.refineQuest(quest("a"), "make it shorter")
        assertTrue(result.error != null)
    }

    @Test
    fun `ai diagnostics dump is empty with the noop diagnostics`() = runTest {
        assertTrue(repo.aiDiagnosticsDump().isBlank())
        repo.clearAiDiagnostics()
    }

    @Test
    fun `removing a habit drops its derived quest from the plan`() = runTest {
        repo.addHabit(
            com.questloop.core.model.Habit(
                id = "h1",
                title = "Stretch daily",
                category = QuestCategory.HEALTH,
                targetPerWeek = 7,
            ),
        )
        assertTrue(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "habit-h1" })
        repo.removeHabit("h1")
        assertFalse(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "habit-h1" })
    }

    @Test
    fun `bad habit becomes a derived reduction quest`() = runTest {
        repo.addBadHabit(
            com.questloop.core.model.BadHabit(id = "b1", title = "Doomscrolling", dailyLimit = 1),
        )
        assertTrue(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "badhabit-b1" })
        repo.removeBadHabit("b1")
        assertFalse(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "badhabit-b1" })
    }

    @Test
    fun `removing a goal drops its derived quest`() = runTest {
        repo.addGoal(
            com.questloop.core.model.Goal(id = "g1", title = "Learn guitar", category = QuestCategory.PERSONAL_GROWTH),
        )
        assertTrue(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "goal-g1" })
        repo.removeGoal("g1")
        assertFalse(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "goal-g1" })
    }

    @Test
    fun `setting focus categories persists through the repository`() = runTest {
        repo.setFocusCategories(setOf(QuestCategory.HEALTH, QuestCategory.WORK_STUDY))
        val cats = repo.profile.first().preferences.focusCategories
        assertTrue(cats.contains(QuestCategory.HEALTH))
    }
}
