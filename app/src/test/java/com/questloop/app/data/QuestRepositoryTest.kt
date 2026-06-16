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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        private val state = MutableStateFlow(UserProfile(preferences = UserPreferences(monthlyRewardBudgetCap = cap)))
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
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
    fun `skipping a quest before earning any xp never goes negative`() = runTest {
        // Regression: skip applied a gentle penalty, the ledger went below zero, and
        // LevelSystem.levelForXp(<0) threw -> the Today screen crashed.
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.SKIPPED)
        assertEquals(0L, repo.totalXp())
        // The level lookup that the UI performs must not throw on the raw ledger.
        com.questloop.core.reward.LevelSystem.progress(repo.totalXp())
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
    fun `concurrent meta completions stay within the daily meta cap`() = runBlocking {
        // The completion mutex must serialize the ledger read-modify-write. Two
        // EPIC meta quests completed at once would each read meta-earned=0 and grant
        // the full 30 (=60, over the cap) without it; serialized, the second sees
        // the first's 30 and grants 0.
        val m1 = quest("m1", category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.EPIC)
        val m2 = quest("m2", category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.EPIC)
        repo.addQuest(m1)
        repo.addQuest(m2)
        listOf(
            launch(Dispatchers.Default) { repo.completeQuest(m1, epochDay = 1, result = CompletionResult.COMPLETED) },
            launch(Dispatchers.Default) { repo.completeQuest(m2, epochDay = 1, result = CompletionResult.COMPLETED) },
        ).joinAll()
        assertEquals(30L, repo.totalXp())
    }

    @Test
    fun `fresh install seeds only the onboarding quests`() = runTest {
        repo.seedIfEmpty()
        assertEquals(
            setOf(SampleData.ONBOARDING_PICK, SampleData.ONBOARDING_CREATE),
            repo.activeQuestIds(),
        )
    }

    @Test
    fun `adding from the bank adds the quest and clears the pick guide`() = runTest {
        repo.seedIfEmpty()
        val bankQuest = QuestBank.catalog.first()
        repo.addFromBank(bankQuest)
        val ids = repo.activeQuestIds()
        assertTrue(bankQuest.id in ids)
        assertFalse(SampleData.ONBOARDING_PICK in ids)
    }

    @Test
    fun `quest overview lists every active quest with its today status`() = runTest {
        repo.addQuest(quest("daily1"))
        repo.addQuest(quest("weekly1").copy(frequency = QuestFrequency.WEEKLY))
        repo.completeQuest(quest("daily1"), epochDay = 1, result = CompletionResult.COMPLETED)

        val overview = repo.questOverview(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertEquals(2, overview.size)
        val daily = overview.first { it.quest.id == "daily1" }
        val weekly = overview.first { it.quest.id == "weekly1" }
        // Completed today -> shown as done and no longer in the curated plan.
        assertTrue(daily.completedToday)
        assertFalse(daily.inTodaysPlan)
        // A never-completed weekly quest is due today and visible in the backlog.
        assertFalse(weekly.completedToday)
        assertTrue(weekly.dueToday)
    }

    @Test
    fun `archived quest drops out of the overview`() = runTest {
        repo.addQuest(quest("keep"))
        repo.addQuest(quest("toss"))
        repo.archiveQuest("toss")
        val overview = repo.questOverview(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(overview.any { it.quest.id == "keep" })
        assertFalse(overview.any { it.quest.id == "toss" })
    }

    @Test
    fun `first completion unlocks the first-steps achievement`() = runTest {
        repo.addQuest(quest("a"))
        val outcome = repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(outcome.newlyUnlocked.any { it.id == "first_steps" })
    }

    @Test
    fun `a habit becomes a derived quest in the plan`() = runTest {
        repo.addHabit(
            com.questloop.core.model.Habit(
                id = "h1",
                title = "Stretch daily",
                category = QuestCategory.HEALTH,
                targetPerWeek = 7,
            ),
        )
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.any { it.quest.id == "habit-h1" })
    }

    @Test
    fun `energy check-in persists for the day only`() = runTest {
        repo.setCheckIn(com.questloop.core.model.EnergyCheckIn(epochDay = 5, energy = 2, availableMinutes = 60))
        val same = repo.todayCheckIn(5)
        assertEquals(2, same?.energy)
        assertEquals(60, same?.availableMinutes)
        assertEquals(null, repo.todayCheckIn(6))
    }

    @Test
    fun `a goal becomes a weekly derived quest`() = runTest {
        repo.addGoal(
            com.questloop.core.model.Goal(id = "g1", title = "Learn guitar", category = QuestCategory.PERSONAL_GROWTH),
        )
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.any { it.quest.id == "goal-g1" })
    }

    @Test
    fun `quest suggestion falls back to deterministic when AI is disabled`() = runTest {
        // No AI configured -> deterministic, safe suggestions (no network).
        val suggestion = repo.suggestQuests(listOf("Email landlord"))
        assertFalse(suggestion.fromAi)
        assertEquals("Email landlord", suggestion.quests.first().title)
    }

    @Test
    fun `reminder config round-trips`() = runTest {
        assertFalse(repo.reminderConfig().enabled)
        repo.setReminderConfig(ReminderConfig(enabled = true, morningHour = 7, eveningHour = 21))
        val cfg = repo.reminderConfig()
        assertTrue(cfg.enabled)
        assertEquals(7, cfg.morningHour)
        assertEquals(21, cfg.eveningHour)
    }

    @Test
    fun `current streak counts a completion today`() = runTest {
        repo.addQuest(quest("a"))
        assertEquals(0, repo.currentStreak(1))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertEquals(1, repo.currentStreak(1))
    }

    @Test
    fun `achievement statuses cover all and reflect unlocks`() = runTest {
        val before = repo.achievementStatuses()
        assertTrue(before.isNotEmpty())
        assertTrue(before.none { it.unlocked })
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val after = repo.achievementStatuses()
        assertEquals(before.size, after.size)
        assertTrue(after.any { it.achievement.id == "first_steps" && it.unlocked })
    }

    @Test
    fun `saving ai config makes it usable`() = runTest {
        assertFalse(repo.aiConfig().usable)
        repo.setAiConfig(AiConfig(enabled = true, apiKey = "sk-test", model = "m"))
        val cfg = repo.aiConfig()
        assertTrue(cfg.usable)
        assertEquals("sk-test", cfg.apiKey)
    }

    @Test
    fun `onboarding flag flips once completed`() = runTest {
        assertFalse(repo.isOnboardingComplete())
        repo.completeOnboarding()
        assertTrue(repo.isOnboardingComplete())
    }

    @Test
    fun `undo of a completion restores prior xp`() = runTest {
        repo.addQuest(quest("a"))
        val outcome = repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(repo.totalXp() > 0)
        repo.undoCompletion(outcome.instanceId, outcome.previousRecord)
        assertEquals(0L, repo.totalXp())
        // The quest is available again after undo.
        assertTrue(repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY).quests.any { it.quest.id == "a" })
    }

    @Test
    fun `export produces json containing quests and completions`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val json = repo.exportJson()
        assertTrue(json.contains("\"quests\""))
        assertTrue(json.contains("Quest a"))
        assertTrue(json.contains("\"completions\""))
    }

    @Test
    fun `delete all data resets xp and quests`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(repo.totalXp() > 0)
        repo.deleteAllData()
        assertEquals(0L, repo.totalXp())
        assertTrue(repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY).quests.isEmpty())
    }
}
