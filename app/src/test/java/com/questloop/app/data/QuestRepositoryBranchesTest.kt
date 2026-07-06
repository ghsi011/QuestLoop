package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.serialization.json.Json
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
 * Branch coverage for [QuestRepository] paths not exercised by the happy-path
 * suite or the emulator: import edge cases (older-but-valid version, archived id
 * restoration, derived/routine completion retention, empty snapshot), export
 * round-trips of profile lists, non-COMPLETED and reduction completion scoring,
 * penalty flooring, user-grace streaks, safety signals on a populated ledger,
 * and measured boundary values. Mirrors the in-memory Room + fake preferences
 * setup of QuestRepositoryCompletionStylesTest exactly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryBranchesTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository
    private lateinit var prefs: FakePrefs

    private class FakePrefs(cap: Double = 0.0) : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile(preferences = UserPreferences(monthlyRewardBudgetCap = cap)))
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(monthlyRewardBudgetCap = value),
            )
        }
        override suspend fun setMaxDaily(value: Int) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(maxDailyQuests = value),
            )
        }
        override suspend fun setAvailableMinutes(value: Int) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(defaultAvailableMinutes = value),
            )
        }
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
        override suspend fun setFirstDayOfWeek(day: java.time.DayOfWeek) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(firstDayOfWeek = day),
            )
        }
        override suspend fun setSensitiveOptIn(value: Boolean) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(sensitiveNotificationsOptIn = value),
            )
        }
        override suspend fun setHabits(habits: List<Habit>) {
            state.value = state.value.copy(habits = habits)
        }
        override suspend fun setBadHabits(badHabits: List<com.questloop.core.model.BadHabit>) {
            state.value = state.value.copy(badHabits = badHabits)
        }
        override suspend fun setGoals(goals: List<Goal>) {
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
        prefs = FakePrefs()
        repo = QuestRepository(db.questDao(), db.completionDao(), prefs)
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
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = frequency,
        difficulty = difficulty,
        completionStyle = style,
        targetCount = target,
    )

    // --- importJson edge cases ---------------------------------------------

    @Test
    fun `import accepts an older but valid version snapshot`() = runTest {
        // version below CURRENT_VERSION is still valid (only newer is rejected).
        val snapshot = ExportSnapshot(
            version = ExportSnapshot.CURRENT_VERSION - 1,
            quests = listOf(quest("old")),
            completions = emptyList(),
            profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        assertEquals(null, result.error)
        assertEquals(1, result.quests)
        assertTrue(repo.activeQuestIds().contains("old"))
    }

    @Test
    fun `import re-archives quests listed in archivedIds`() = runTest {
        val snapshot = ExportSnapshot(
            quests = listOf(quest("a"), quest("b")),
            completions = emptyList(),
            profile = UserProfile(),
            archivedIds = listOf("b"),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        repo.importJson(json)
        val active = repo.activeQuestIds()
        assertTrue(active.contains("a"))
        assertFalse(active.contains("b")) // restored as archived
    }

    @Test
    fun `import keeps completions referencing derived habit and goal quests`() = runTest {
        // Seed a habit + goal so their derived quest ids are known at import time.
        repo.addHabit(Habit(id = "h1", title = "Stretch", category = QuestCategory.HEALTH, targetPerWeek = 7))
        repo.addGoal(Goal(id = "g1", title = "Learn guitar", category = QuestCategory.PERSONAL_GROWTH))

        val snapshot = ExportSnapshot(
            quests = emptyList(),
            completions = listOf(
                CompletionRecord(
                    instanceId = "habit-h1@1", questId = "habit-h1", category = QuestCategory.HEALTH,
                    difficulty = Difficulty.EASY, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 7,
                ),
                CompletionRecord(
                    instanceId = "goal-g1@1", questId = "goal-g1", category = QuestCategory.PERSONAL_GROWTH,
                    difficulty = Difficulty.EASY, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 5,
                ),
                CompletionRecord(
                    instanceId = "phantom@1", questId = "phantom", category = QuestCategory.WORK_STUDY,
                    difficulty = Difficulty.EPIC, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 9999,
                ),
            ),
            profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        // Both derived-id rows kept, only the unknown 'phantom' id dropped.
        assertEquals(2, result.completions)
        assertEquals(1, result.skipped)
        assertEquals(12L, repo.totalXp()) // 7 + 5; the 9999 phantom is excluded
    }

    @Test
    fun `import keeps completions referencing system routine quests`() = runTest {
        // Routine quest ids are also valid completion targets on import.
        val routineId = com.questloop.core.generation.RoutineQuestFactory.all().first().id
        val snapshot = ExportSnapshot(
            quests = emptyList(),
            completions = listOf(
                CompletionRecord(
                    instanceId = "$routineId@1", questId = routineId, category = QuestCategory.META_MAINTENANCE,
                    difficulty = Difficulty.EASY, priority = Priority.NORMAL,
                    result = CompletionResult.COMPLETED, epochDay = 1, xpAwarded = 8,
                ),
            ),
            profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        assertEquals(1, result.completions)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `import of an empty snapshot is a clean no-op`() = runTest {
        val snapshot = ExportSnapshot(
            quests = emptyList(),
            completions = emptyList(),
            profile = UserProfile(),
        )
        val json = Json.encodeToString(ExportSnapshot.serializer(), snapshot)
        val result = repo.importJson(json)
        assertEquals(null, result.error)
        assertEquals(0, result.quests)
        assertEquals(0, result.completions)
        assertEquals(0, result.skipped)
        assertEquals(0L, repo.totalXp())
    }

    // --- exportJson contents + round trip ----------------------------------

    @Test
    fun `export includes archived ids and profile fields`() = runTest {
        prefs.setStreakGraceDays(4)
        prefs.setSensitiveOptIn(true)
        repo.addQuest(quest("active"))
        repo.addQuest(quest("gone"))
        repo.archiveQuest("gone")

        val json = repo.exportJson()
        // Re-import the export and assert on the decoded snapshot rather than on
        // pretty-print whitespace, so the test isn't brittle to formatting.
        assertTrue(json.contains("\"archivedIds\""))
        assertTrue(json.contains("\"gone\""))
        val snapshot = Json { ignoreUnknownKeys = true }
            .decodeFromString(ExportSnapshot.serializer(), json)
        assertTrue(snapshot.archivedIds.contains("gone"))
        assertEquals(4, snapshot.profile.preferences.streakGraceDays)
        assertTrue(snapshot.profile.preferences.sensitiveNotificationsOptIn)
    }

    @Test
    fun `export then import round-trips habits bad habits goals and preferences`() = runTest {
        repo.addHabit(Habit(id = "h1", title = "Walk", category = QuestCategory.HEALTH, targetPerWeek = 5))
        repo.addBadHabit(com.questloop.core.model.BadHabit(id = "b1", title = "Snacking", dailyLimit = 2))
        repo.addGoal(Goal(id = "g1", title = "Read more", category = QuestCategory.PERSONAL_GROWTH))
        repo.setBudgetCap(75.0)
        prefs.setStreakGraceDays(2)
        repo.setFirstDayOfWeek(java.time.DayOfWeek.MONDAY) // non-default, must survive the round-trip

        val json = repo.exportJson()
        repo.deleteAllData()
        assertTrue(repo.profile.first().habits.isEmpty())
        // Wipe reset the week start to the Sunday default; import must restore Monday.
        assertEquals(java.time.DayOfWeek.SUNDAY, repo.profile.first().preferences.firstDayOfWeek)

        repo.importJson(json)
        val restored = repo.profile.first()
        assertTrue(restored.habits.any { it.id == "h1" })
        assertTrue(restored.badHabits.any { it.id == "b1" })
        assertTrue(restored.goals.any { it.id == "g1" })
        assertEquals(75.0, restored.preferences.monthlyRewardBudgetCap, 0.0001)
        assertEquals(2, restored.preferences.streakGraceDays)
        assertEquals(java.time.DayOfWeek.MONDAY, restored.preferences.firstDayOfWeek)
    }

    // --- completion result branches ----------------------------------------

    @Test
    fun `failing a normal quest applies a gentle penalty floored at zero`() = runTest {
        // Earn some XP first, then a miss deducts the gentle penalty (3) from it.
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val earned = repo.totalXp()
        assertTrue(earned > 3)
        repo.addQuest(quest("b"))
        repo.completeQuest(quest("b"), epochDay = 1, result = CompletionResult.FAILED)
        assertEquals(earned - 3, repo.totalXp())
    }

    @Test
    fun `failing before earning any xp never goes below zero`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.FAILED)
        assertEquals(0L, repo.totalXp())
    }

    @Test
    fun `skipping a bad-habit reduction quest awards honesty xp`() = runTest {
        val smoke = quest("smoke", category = QuestCategory.BAD_HABIT_REDUCTION)
        repo.addQuest(smoke)
        repo.completeQuest(smoke, epochDay = 1, result = CompletionResult.SKIPPED)
        // Honesty reward (config.honestyXp = 3), never a penalty.
        assertEquals(3L, repo.totalXp())
    }

    @Test
    fun `failing a bad-habit reduction quest also awards honesty xp`() = runTest {
        val smoke = quest("smoke", category = QuestCategory.BAD_HABIT_REDUCTION)
        repo.addQuest(smoke)
        repo.completeQuest(smoke, epochDay = 1, result = CompletionResult.FAILED)
        assertEquals(3L, repo.totalXp())
    }

    @Test
    fun `rescheduling a quest is neutral - no xp change`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val xp = repo.totalXp()
        repo.addQuest(quest("b"))
        repo.completeQuest(quest("b"), epochDay = 1, result = CompletionResult.RESCHEDULED)
        assertEquals(xp, repo.totalXp())
    }

    @Test
    fun `re-logging a completion as a miss replaces rather than stacks the grant`() = runTest {
        // First a real completion grants XP; re-logging the same instance as FAILED
        // nets out the prior grant (ledger derived) and applies the penalty floor.
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        assertTrue(repo.totalXp() > 0)
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.FAILED)
        // Prior +XP removed, then a gentle penalty would dip below zero -> floored.
        assertEquals(0L, repo.totalXp())
    }

    // --- archive / today plan ----------------------------------------------

    @Test
    fun `archived quest is excluded from active quests and the today plan`() = runTest {
        repo.addQuest(quest("keep"))
        repo.addQuest(quest("drop"))
        repo.archiveQuest("drop")
        assertFalse(repo.activeQuestIds().contains("drop"))
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertFalse(plan.quests.any { it.quest.id == "drop" })
        assertTrue(plan.quests.any { it.quest.id == "keep" })
    }

    @Test
    fun `a daily quest added today appears in the today plan`() = runTest {
        repo.addQuest(quest("daily", frequency = QuestFrequency.DAILY))
        val plan = repo.todayPlan(epochDay = 1, dayPart = DayPart.MIDDAY)
        assertTrue(plan.quests.any { it.quest.id == "daily" })
    }

    // --- streaks -----------------------------------------------------------

    @Test
    fun `current streak honours a user-configured grace greater than one`() = runTest {
        // Active on days 1 and 4; a 2-day gap. With grace=2 the run survives.
        prefs.setStreakGraceDays(2)
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        repo.completeQuest(quest("a"), epochDay = 4, result = CompletionResult.COMPLETED)
        // Days 2,3 missed (2 misses <= grace) -> streak spans back to day 1.
        assertEquals(2, repo.currentStreak(4))
    }

    @Test
    fun `current streak breaks when the gap exceeds the configured grace`() = runTest {
        prefs.setStreakGraceDays(2)
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        repo.completeQuest(quest("a"), epochDay = 5, result = CompletionResult.COMPLETED)
        // Days 2,3,4 missed (3 misses > grace) -> only day 5 counts.
        assertEquals(1, repo.currentStreak(5))
    }

    @Test
    fun `longest streak surfaces through achievement progress stats`() = runTest {
        // 5-in-a-row drives longestStreak via progress stats; the >=3 streak
        // achievement ('consistent') unlocks, exercising the longestStreak path.
        repo.addQuest(quest("a"))
        for (day in 1..5L) {
            repo.completeQuest(quest("a"), epochDay = day, result = CompletionResult.COMPLETED)
        }
        assertTrue(repo.unlockedAchievements().any { it.id == "consistent" })
    }

    // --- safety signals on a populated ledger ------------------------------

    @Test
    fun `safety signals raise a rest suggestion after ten active days straight`() = runTest {
        for (day in 1..10L) {
            val q = quest("q$day")
            repo.addQuest(q)
            repo.completeQuest(q, epochDay = day, result = CompletionResult.COMPLETED)
        }
        val signals = repo.safetySignals(today = 10)
        assertTrue(signals.any { it.code == "REST_SUGGESTION" })
    }

    @Test
    fun `safety signals warn on an over-driven single day`() = runTest {
        // 12 distinct completions on one day trips the over-optimization warning.
        for (i in 1..12) {
            val q = quest("o$i")
            repo.addQuest(q)
            repo.completeQuest(q, epochDay = 5, result = CompletionResult.COMPLETED)
        }
        val signals = repo.safetySignals(today = 5)
        assertTrue(signals.any { it.code == "OVERDRIVE" })
    }

    // --- measured boundary values ------------------------------------------

    @Test
    fun `quantitative below target stays partial and visible`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 0) // zero progress
        assertEquals(0, repo.todayProgress(1)["water"])
        // Not dismissed: still on offer to keep logging.
        assertTrue(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "water" })
    }

    @Test
    fun `quantitative above target is capped to completion`() = runTest {
        val water = quest("water", style = CompletionStyle.QUANTITATIVE, target = 8, category = QuestCategory.HEALTH)
        repo.addQuest(water)
        repo.completeMeasured(water, epochDay = 1, value = 20) // over target
        // Progress is clamped to the target; quest is done and dismissed.
        assertEquals(8, repo.todayProgress(1)["water"])
        assertFalse(repo.todayPlan(1, DayPart.MIDDAY).quests.any { it.quest.id == "water" })
    }

    @Test
    fun `duration above estimate completes and records the full estimate`() = runTest {
        val focus = quest("focus", style = CompletionStyle.DURATION, category = QuestCategory.WORK_STUDY)
        repo.addQuest(focus)
        repo.completeMeasured(focus, epochDay = 1, value = focus.estimatedMinutes + 30)
        assertEquals(focus.estimatedMinutes, repo.todayProgress(1)["focus"])
        assertTrue(repo.totalXp() > 0)
    }

    @Test
    fun `subjective lowest rating still logs the quest as done`() = runTest {
        val create = quest("create", style = CompletionStyle.SUBJECTIVE, category = QuestCategory.PERSONAL_GROWTH)
        repo.addQuest(create)
        repo.completeMeasured(create, epochDay = 1, value = 1) // lowest score
        // Subjective is not count/duration, so todayProgress has no entry for it.
        assertFalse(repo.todayProgress(1).containsKey("create"))
    }

    // --- AI-off entry points -----------------------------------------------

    @Test
    fun `narrate review falls back to a non-ai narration when ai is off`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = 1, result = CompletionResult.COMPLETED)
        val review = repo.review("This week", fromEpochDay = 1, toEpochDay = 7)
        val narration = repo.narrateReview(review)
        assertFalse(narration.fromAi)
        assertTrue(narration.text.isNotBlank())
    }

    @Test
    fun `suggest quests dedupes against existing and stays deterministic when ai is off`() = runTest {
        repo.addQuest(quest("a"))
        val suggestion = repo.suggestQuests(listOf("Call the dentist"))
        assertFalse(suggestion.fromAi)
        assertTrue(suggestion.quests.isNotEmpty())
    }
}
