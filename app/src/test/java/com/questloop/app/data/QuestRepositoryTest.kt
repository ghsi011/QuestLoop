package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.CompletionRecord
import com.questloop.core.generation.AdminFundFactory
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun `export then import restores quests and xp`() = runTest {
        repo.addQuest(quest("a"))
        repo.addQuest(quest("b"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val xp = repo.totalXp()
        assertTrue(xp > 0)
        val json = repo.exportJson()

        repo.deleteAllData()
        assertEquals(0L, repo.totalXp())

        val result = repo.importJson(json)
        assertEquals(null, result.error)
        assertEquals(2, result.quests)
        assertEquals(xp, repo.totalXp())
        assertTrue(repo.activeQuestIds().containsAll(setOf("a", "b")))
    }

    @Test
    fun `import is idempotent and rejects junk`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val xp = repo.totalXp()
        val json = repo.exportJson()
        // Re-importing the same backup must not double-count XP (idempotent by instanceId).
        repo.importJson(json)
        repo.importJson(json)
        assertEquals(xp, repo.totalXp())
        // Malformed input is rejected with a message, no crash.
        val bad = repo.importJson("not json at all")
        assertTrue(bad.error != null)
    }

    @Test
    fun `import drops completions with no backing quest`() = runTest {
        // A hand-edited snapshot can't inject phantom XP for a quest that doesn't exist.
        val snapshot = ExportSnapshot(
            quests = listOf(quest("keep")),
            completions = listOf(
                CompletionRecord(
                    instanceId = "keep@1", questId = "keep", category = QuestCategory.WORK_STUDY,
                    difficulty = Difficulty.MEDIUM, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 20,
                ),
                CompletionRecord(
                    instanceId = "ghost@1", questId = "ghost", category = QuestCategory.WORK_STUDY,
                    difficulty = Difficulty.EPIC, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 9999,
                ),
            ),
            profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        assertEquals(1, result.completions)
        assertEquals(1, result.skipped)
        assertEquals(20L, repo.totalXp()) // the 9999 phantom row was dropped
    }

    @Test
    fun `import restores grace days and sensitive opt-in`() = runTest {
        val snapshot = ExportSnapshot(
            quests = emptyList(),
            completions = emptyList(),
            profile = UserProfile(
                preferences = UserPreferences(streakGraceDays = 3, sensitiveNotificationsOptIn = true),
            ),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        repo.importJson(json)
        val prefs = repo.profile.first().preferences
        assertEquals(3, prefs.streakGraceDays)
        assertTrue(prefs.sensitiveNotificationsOptIn)
    }

    @Test
    fun `import rejects a newer-version snapshot`() = runTest {
        val snapshot = ExportSnapshot(
            version = ExportSnapshot.CURRENT_VERSION + 1,
            quests = emptyList(), completions = emptyList(), profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        assertTrue(result.error != null)
        assertEquals(0, result.quests)
    }

    @Test
    fun `archived quests survive an export-import round-trip`() = runTest {
        repo.addQuest(quest("archived"))
        repo.archiveQuest("archived")
        repo.addQuest(quest("active"))
        val json = repo.exportJson()
        repo.deleteAllData()

        repo.importJson(json)
        val active = repo.activeQuestIds()
        assertTrue(active.contains("active"))
        assertFalse(active.contains("archived")) // re-archived, not lost
    }

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
    fun `adding from the bank adds the quest and completes the pick guide for xp`() = runTest {
        repo.seedIfEmpty()
        val bankQuest = QuestBank.catalog.first()
        repo.addFromBank(bankQuest, epochDay = 1)
        val ids = repo.activeQuestIds()
        assertTrue(bankQuest.id in ids)
        // The guide is completed (XP) then archived, so it's gone from the list.
        assertFalse(SampleData.ONBOARDING_PICK in ids)
        assertTrue("picking the first quest awards XP", repo.totalXp() > 0)
    }

    @Test
    fun `completing an onboarding guide awards xp and removes it`() = runTest {
        repo.seedIfEmpty()
        repo.completeOnboardingQuest(SampleData.ONBOARDING_CREATE, epochDay = 1)
        assertTrue(repo.totalXp() > 0)
        assertFalse(SampleData.ONBOARDING_CREATE in repo.activeQuestIds())
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
        // Completed today -> marked done and no longer in the curated plan.
        assertTrue(daily.done)
        assertFalse(daily.inTodaysPlan)
        // A never-completed weekly quest is due today and visible in the backlog.
        assertFalse(weekly.done)
        assertTrue(weekly.dueToday)
    }

    @Test
    fun `overview keeps a partially logged counting quest visible with its progress`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 3) // 3/8 -> partial, not done
        val status = repo.questOverview(epochDay = 1, dayPart = DayPart.MIDDAY).first { it.quest.id == "water" }
        assertFalse(status.done)
        assertEquals(3, status.progress)

        repo.completeMeasured(water, epochDay = 1, value = 8) // reaches target -> done
        val done = repo.questOverview(epochDay = 1, dayPart = DayPart.MIDDAY).first { it.quest.id == "water" }
        assertTrue(done.done)
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
    fun `export never contains the ai api key`() = runTest {
        repo.setAiConfig(AiConfig(enabled = true, apiKey = "sk-super-secret-key", model = "m"))
        repo.addQuest(quest("a"))
        val json = repo.exportJson()
        assertFalse(json.contains("sk-super-secret-key"))
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

    @Test
    fun `no reward-fund admin quests until a budget is set`() = runTest {
        // Default repo has a zero budget cap.
        val plan = repo.periodPlan("This month", fromEpochDay = 1, toEpochDay = 31)
        assertTrue(plan.items.none { it.quest.id.startsWith(AdminFundFactory.PREFIX) })
    }

    @Test
    fun `setting a budget surfaces the open-pot admin quest`() = runTest {
        val funded = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(cap = 50.0))
        val plan = funded.periodPlan("This month", fromEpochDay = 1, toEpochDay = 31)
        assertTrue(plan.items.any { it.quest.id == AdminFundFactory.OPEN_POT_ID })
        assertTrue(plan.items.none { it.quest.id == AdminFundFactory.FUND_MONTH_ID })
    }

    @Test
    fun `opening the pot advances to monthly funding`() = runTest {
        val funded = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(cap = 50.0))
        funded.completeQuest(AdminFundFactory.openPotQuest(), epochDay = 1, result = CompletionResult.COMPLETED)
        val plan = funded.periodPlan("This month", fromEpochDay = 2, toEpochDay = 32)
        assertTrue(plan.items.none { it.quest.id == AdminFundFactory.OPEN_POT_ID })
        assertTrue(plan.items.any { it.quest.id == AdminFundFactory.FUND_MONTH_ID })
    }

    @Test
    fun `funding stays done within the month then recurs`() = runTest {
        val funded = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(cap = 50.0))
        funded.completeQuest(AdminFundFactory.openPotQuest(), epochDay = 1, result = CompletionResult.COMPLETED)
        funded.completeQuest(AdminFundFactory.fundMonthQuest(), epochDay = 2, result = CompletionResult.COMPLETED)
        // Within the monthly period -> funding step is not due again.
        val soon = funded.periodPlan("m", fromEpochDay = 10, toEpochDay = 20)
        assertTrue(soon.items.none { it.quest.id == AdminFundFactory.FUND_MONTH_ID })
        // Past the ~30-day period -> it returns.
        val later = funded.periodPlan("m", fromEpochDay = 33, toEpochDay = 63)
        assertTrue(later.items.any { it.quest.id == AdminFundFactory.FUND_MONTH_ID })
    }

    @Test
    fun `calendar free time caps today's plan`() = runTest {
        val reader = object : CalendarReader {
            override suspend fun freeMinutesToday(): Int = 20 // only 20 minutes free today
            override suspend fun upcomingEvents(daysAhead: Int): List<CalendarEventSummary> = emptyList()
        }
        val withCal = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(), calendarReader = reader)
        repeat(5) { withCal.addQuest(quest("q$it")) } // MEDIUM ~25 min each
        val plan = withCal.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        // A hard 20-minute budget admits only the first quest (the generator never
        // returns an empty day, but won't pile more on past the budget).
        assertEquals(1, plan.quests.size)
    }

    @Test
    fun `completing admin steps does not manufacture an earned allowance`() = runTest {
        val funded = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(cap = 50.0))
        funded.completeQuest(AdminFundFactory.openPotQuest(), epochDay = 1, result = CompletionResult.COMPLETED)
        funded.completeQuest(AdminFundFactory.fundMonthQuest(), epochDay = 1, result = CompletionResult.COMPLETED)
        // Meta/admin completions are excluded, so nothing has been "earned".
        assertEquals(0.0, funded.allowance(fromEpochDay = 1, toEpochDay = 31).suggestedAllowance, 0.0001)
    }
}
