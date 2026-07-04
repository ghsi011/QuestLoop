package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.CompletionRecord
import com.questloop.core.generation.AdminFundFactory
import com.questloop.core.generation.QuestScheduler
import com.questloop.core.generation.RoutineQuestFactory
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
import org.junit.Assert.assertNotNull
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
        frequency: QuestFrequency = QuestFrequency.DAILY,
        allowOverCompletion: Boolean = false,
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = frequency,
        difficulty = difficulty,
        completionStyle = style,
        targetCount = target,
        allowOverCompletion = allowOverCompletion,
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
    fun `delete all data removes the ai diagnostics log`() = runTest {
        val diagnostics = FileAiDiagnostics(RuntimeEnvironment.getApplication())
        diagnostics.clear()
        val repoWithLog = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(), aiDiagnostics = diagnostics)
        diagnostics.record("some-model", "provider error body")
        assertTrue(repoWithLog.aiDiagnosticsDump().isNotBlank())

        repoWithLog.deleteAllData()

        assertTrue("a full wipe removes the AI error log", repoWithLog.aiDiagnosticsDump().isBlank())
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
    fun `ledger change stamp ticks on every write including an idempotent re-log`() = runTest {
        // The widget-freshness trigger observes completionsChanged — backed by this
        // stamp — instead of re-mapping the whole ledger, so the stamp must move on
        // ANY write, even one that leaves COUNT(*) and SUM(xpAwarded) unchanged.
        val dao = db.completionDao()
        val empty = dao.observeChangeStamp().first()

        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val afterFirst = dao.observeChangeStamp().first()
        assertTrue("a new completion moves the stamp", afterFirst != empty)

        // Idempotent re-log: same instanceId and same XP total — but the REPLACE
        // upsert mints a fresh rowid, so the widget would still refresh.
        val xp = repo.totalXp()
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertEquals(xp, repo.totalXp())
        val afterRelog = dao.observeChangeStamp().first()
        assertTrue("an idempotent re-log still moves the stamp", afterRelog != afterFirst)

        // The repository surfaces the stamp as a signal-only flow.
        repo.completionsChanged.first()
    }

    @Test
    fun `completed quest is dismissed from today's plan`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.none { it.quest.id == "a" })
    }

    @Test
    fun `a completed daily routine leaves today's plan and returns tomorrow`() = runTest {
        // Regression: routine quests aren't stored (or habit-derived), so the
        // dismissal scan must still cover them — else the morning check-in pops
        // right back into the plan after being checked off.
        val morning = RoutineQuestFactory.all().first { it.id == RoutineQuestFactory.MORNING_REVIEW }
        val before = repo.todayPlan(epochDay = 1, dayPart = DayPart.MORNING)
        assertTrue(before.quests.any { it.quest.id == morning.id })

        repo.completeQuest(morning, epochDay = 1, result = CompletionResult.COMPLETED)
        val after = repo.todayPlan(epochDay = 1, dayPart = DayPart.MORNING)
        assertTrue(after.quests.none { it.quest.id == morning.id })

        // Dismissed for the day only — it's back tomorrow.
        val nextDay = repo.todayPlan(epochDay = 2, dayPart = DayPart.MORNING)
        assertTrue(nextDay.quests.any { it.quest.id == morning.id })
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
    fun `plan resolves the persisted check-in and an explicit one overrides it`() = runTest {
        // An easy companion keeps the low-energy plan non-empty (an empty plan
        // would trigger the easiest-quest fallback and resurface the hard one).
        repo.addQuest(quest("gentle", difficulty = Difficulty.EASY))
        repo.addQuest(quest("tough", difficulty = Difficulty.HARD))
        // No check-in today: the hard quest is planned as usual.
        assertTrue(repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY).quests.any { it.quest.id == "tough" })
        // A persisted low-energy check-in caps difficulty at MEDIUM. todayPlan
        // resolves it itself, so callers that don't thread it (widget, reminder
        // receiver) see the same lighter plan as the Today screen.
        repo.setCheckIn(com.questloop.core.model.EnergyCheckIn(epochDay = 1, energy = 2, availableMinutes = 60))
        val lighter = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(lighter.quests.any { it.quest.id == "gentle" })
        assertFalse(lighter.quests.any { it.quest.id == "tough" })
        // An explicitly passed check-in overrides the persisted one.
        val energized = com.questloop.core.model.EnergyCheckIn(epochDay = 1, energy = 5, availableMinutes = 240)
        assertTrue(
            repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY, checkIn = energized)
                .quests.any { it.quest.id == "tough" },
        )
        // A check-in persisted for another day never shapes today's plan.
        repo.setCheckIn(com.questloop.core.model.EnergyCheckIn(epochDay = 3, energy = 2, availableMinutes = 60))
        assertTrue(repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY).quests.any { it.quest.id == "tough" })
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
    fun `a skipped admin step rests for the day then returns`() = runTest {
        // Admin-fund quests aren't stored either, and isDue only tracks
        // completions — the dismissal scan is what hides a skip for the day.
        val funded = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(cap = 50.0))
        funded.completeQuest(AdminFundFactory.openPotQuest(), epochDay = 1, result = CompletionResult.SKIPPED)
        val today = funded.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(today.quests.none { it.quest.id == AdminFundFactory.OPEN_POT_ID })

        // A skip rests it for the day, not forever.
        val nextDay = funded.todayPlan(epochDay = 2, dayPart = DayPart.MIDDAY)
        assertTrue(nextDay.quests.any { it.quest.id == AdminFundFactory.OPEN_POT_ID })
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

    // A Monday, so week math is unambiguous (ISO week starts Monday).
    private val monday = java.time.LocalDate.of(2026, 6, 22).toEpochDay()

    private fun weeklySwim(allowOver: Boolean = false) = quest(
        id = "swim",
        style = CompletionStyle.QUANTITATIVE,
        target = 2,
        category = QuestCategory.HEALTH,
        frequency = QuestFrequency.WEEKLY,
        allowOverCompletion = allowOver,
    )

    @Test
    fun `a weekly quantitative quest accumulates across the week then completes`() = runTest {
        val swim = weeklySwim()
        repo.addQuest(swim)
        val wednesday = monday + 2

        // One swim on Monday — still 1/2 when viewed on Wednesday (same week), not reset.
        repo.completeMeasured(swim, monday, 1)
        assertEquals(1, repo.todayProgress(wednesday)["swim"])

        // A second swim hits the weekly target -> completed, leaves the plan.
        repo.completeMeasured(swim, wednesday, 2)
        assertEquals(2, repo.todayProgress(wednesday)["swim"])
        assertFalse(repo.todayPlan(wednesday, DayPart.MIDDAY).quests.any { it.quest.id == "swim" })

        // Next week resets: due again, no carried-over progress.
        val nextMonday = monday + 7
        assertEquals(null, repo.todayProgress(nextMonday)["swim"])
        assertTrue(repo.todayPlan(nextMonday, DayPart.MIDDAY).quests.any { it.quest.id == "swim" })
    }

    @Test
    fun `over-completion keeps a weekly quest loggable past its target and shows the raw count`() = runTest {
        val swim = weeklySwim(allowOver = true)
        repo.addQuest(swim)

        repo.completeMeasured(swim, monday, 2) // hit the target
        repo.completeMeasured(swim, monday, 3) // a third swim, over the target

        assertEquals(3, repo.todayProgress(monday)["swim"]) // shows 3, not capped at 2
        // Still in the plan — over-completion stays loggable for the interval.
        assertTrue(repo.todayPlan(monday, DayPart.MIDDAY).quests.any { it.quest.id == "swim" })

        // Over-completing earns a little more XP than exactly hitting the target,
        // but the bonus is bounded (not linear).
        val overXp = repo.totalXp()
        repo.deleteAllData()
        val plain = weeklySwim()
        repo.addQuest(plain)
        repo.completeMeasured(plain, monday, 2)
        val targetXp = repo.totalXp()
        assertTrue("over-completion should beat exactly hitting target", overXp > targetXp)
        assertTrue("bonus must not be linear", overXp < targetXp * 2)
    }

    @Test
    fun `a weekly measured completion is dated to the real log day, not the interval start`() = runTest {
        val swim = weeklySwim()
        repo.addQuest(swim)
        val wednesday = monday + 2

        repo.completeMeasured(swim, wednesday, 2) // hit the weekly target on Wednesday

        // The record accumulates under the Monday interval slot (its instanceId), but
        // is *dated* to Wednesday — so the completed-history row and the streak/active
        // -day math reflect the real day, not the interval start (finding: don't shift
        // epochDay to the slot, which would break the streak and mis-date history).
        val entry = repo.completedHistory().first { it.record.questId == "swim" }
        assertEquals(wednesday, entry.record.epochDay)
        assertEquals("Wednesday must count as an active day", 1, repo.currentStreak(wednesday))
    }

    @Test
    fun `a weekly measured quest resurfaces next interval even when the rolling window says not-due`() = runTest {
        val swim = weeklySwim()
        repo.addQuest(swim)
        val sunday = monday + 6

        // A single partial swim late in the week (1/2), dated to Sunday.
        repo.completeMeasured(swim, sunday, 1)

        // The next calendar week begins the very next day. The rolling isDue window
        // (7 days from the last completion) would still hide it, but visibility for a
        // measured weekly quest is governed by the calendar interval, so it comes back
        // as a fresh, visible 0/2 (finding: don't let isDue gate the interval reset).
        val nextMonday = monday + 7
        assertFalse(
            "isDue rolling window alone would still gate it",
            QuestScheduler.isDue(QuestFrequency.WEEKLY, nextMonday, sunday),
        )
        assertEquals(null, repo.todayProgress(nextMonday)["swim"])
        assertTrue(repo.todayPlan(nextMonday, DayPart.MIDDAY).quests.any { it.quest.id == "swim" })
    }

    @Test
    fun `a weekly measured quest accumulates across separate log days into one interval record`() = runTest {
        val swim = weeklySwim()
        repo.addQuest(swim)
        val wednesday = monday + 2

        repo.completeMeasured(swim, monday, 1) // 1/2 on Monday
        repo.completeMeasured(swim, wednesday, 2) // second swim on Wednesday -> 2/2

        // Progress accumulates into the single weekly record (2/2), and — because a
        // measured interval quest is one logical unit stored as one row — that row is
        // dated to the most recent log day (Wednesday), keeping today's streak alive.
        assertEquals(2, repo.todayProgress(wednesday)["swim"])
        val entry = repo.completedHistory().single { it.record.questId == "swim" }
        assertEquals(wednesday, entry.record.epochDay)
    }

    private fun oneOffRead() = quest(
        id = "read",
        style = CompletionStyle.QUANTITATIVE,
        target = 100,
        category = QuestCategory.PERSONAL_GROWTH,
        frequency = QuestFrequency.ONE_OFF,
    )

    @Test
    fun `a one-off quantitative quest accumulates across days then completes for good`() = runTest {
        val read = oneOffRead()
        repo.addQuest(read)

        // 30 pages on day 1 — the next day it's still 30/100 (not reset) and still offered.
        repo.completeMeasured(read, epochDay = 1, value = 30)
        assertEquals(30, repo.todayProgress(2)["read"])
        assertTrue(repo.todayPlan(2, DayPart.MIDDAY).quests.any { it.quest.id == "read" })

        // Reaching the cumulative target days later completes it — and a one-off has
        // no next interval, so it leaves the plan for good (isDue via lastCompleted).
        repo.completeMeasured(read, epochDay = 4, value = 100)
        val done = repo.questOverview(5, DayPart.MIDDAY).first { it.quest.id == "read" }
        assertTrue(done.done)
        assertFalse(done.dueToday)
        assertFalse(repo.todayPlan(5, DayPart.MIDDAY).quests.any { it.quest.id == "read" })
        assertFalse(repo.todayPlan(40, DayPart.MIDDAY).quests.any { it.quest.id == "read" })
    }

    @Test
    fun `a one-off measured quest logged across days nets one grant, not one per day`() = runTest {
        val read = oneOffRead()
        repo.addQuest(read)

        // Progress logged over three days accumulates into ONE ledger row whose grant
        // is re-netted on each log — day-keyed records used to score every partial
        // day independently and mint more XP than finishing the quest is worth.
        repo.completeMeasured(read, epochDay = 1, value = 30)
        repo.completeMeasured(read, epochDay = 2, value = 60)
        repo.completeMeasured(read, epochDay = 3, value = 100)

        val rows = db.completionDao().all().filter { it.questId == "read" }
        assertEquals(1, rows.size)
        assertEquals(CompletionResult.COMPLETED.name, rows.single().result)
        // Total XP is exactly that single grant — nothing stacked across the days.
        assertEquals(rows.single().xpAwarded, repo.totalXp())
    }

    @Test
    fun `editing a measured quest's frequency re-keys the record without inflating the reported total`() = runTest {
        val daily = quest("a", style = CompletionStyle.QUANTITATIVE, target = 2, frequency = QuestFrequency.DAILY)
        repo.addQuest(daily)
        val day = monday + 2
        repo.completeMeasured(daily, day, 2) // COMPLETED, keyed a@day
        val xpBefore = repo.totalXp()

        // Change to WEEKLY: the record re-keys from @day to @weekStart.
        val outcome = repo.editQuestAndRescore(daily.copy(frequency = QuestFrequency.WEEKLY), "a@$day")!!

        // The stale @day record is gone and a single @weekStart record remains: XP is
        // unchanged (difficulty same), only one history row survives (no orphan), and
        // the reported total matches the real ledger (no phantom level-up from an
        // un-netted old grant).
        assertEquals(xpBefore, repo.totalXp())
        assertEquals(1, repo.completedHistory().count { it.record.questId == "a" })
        assertEquals(repo.totalXp(), outcome.effect.newTotalXp)
    }

    @Test
    fun `history marks stored quests editable and non-stored (routine) completions not`() = runTest {
        // A routine quest is named for display but isn't in the quests table.
        val routine = RoutineQuestFactory.all().first()
        repo.completeQuest(routine, epochDay = 1, result = CompletionResult.COMPLETED)
        val routineEntry = repo.completedHistory().first { it.record.questId == routine.id }
        assertNotNull("routine still resolves a title", routineEntry.quest)
        assertFalse("routine isn't editable/re-addable", routineEntry.editable)

        repo.addQuest(quest("stored"))
        repo.completeQuest(quest("stored"), epochDay = 1, result = CompletionResult.COMPLETED)
        val storedEntry = repo.completedHistory().first { it.record.questId == "stored" }
        assertTrue("a stored quest is editable/re-addable", storedEntry.editable)
    }
}
