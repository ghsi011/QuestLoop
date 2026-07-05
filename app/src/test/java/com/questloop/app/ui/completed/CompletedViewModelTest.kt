package com.questloop.app.ui.completed

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.BadHabit
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import java.time.LocalDate
import java.util.concurrent.Executor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompletedViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile())
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
        override suspend fun setStreakGraceDays(value: Int) {}
        override suspend fun setSensitiveOptIn(value: Boolean) {}
        override suspend fun setHabits(habits: List<Habit>) {}
        override suspend fun setBadHabits(badHabits: List<BadHabit>) {}
        override suspend fun setGoals(goals: List<Goal>) {}
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {}
        override suspend fun getCheckIn(): EnergyCheckIn? = null
        override suspend fun getAiConfig(): AiConfig = AiConfig()
        override suspend fun setAiConfig(config: AiConfig) {}
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = ReminderConfig()
        override suspend fun setReminderConfig(config: ReminderConfig) {}
        override suspend fun clear() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sync = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor(sync).setTransactionExecutor(sync).build()
        // Unconfined history mapping: like the sync DB executors above, it keeps the
        // VM's fire-and-forget loads fully inline so state can be asserted right away.
        repo = QuestRepository(
            db.questDao(), db.completionDao(), FakePrefs(),
            historyDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // Fixed "today": Sunday 2026-03-01, the 1st of the month — so the WEEK window
    // reaches back into February while the MONTH window is just this one day.
    private val today = LocalDate.of(2026, 3, 1).toEpochDay()

    private fun quest(id: String, difficulty: Difficulty = Difficulty.EASY) = Quest(
        id = id,
        title = "Quest $id",
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = difficulty,
    )

    @Test
    fun `history lists completed quests and undo removes them and their xp`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = today, result = CompletionResult.COMPLETED)
        assertTrue(repo.totalXp() > 0)

        val vm = CompletedViewModel(repo, todayEpochDay = { today })
        assertEquals(1, vm.state.value.entries.size)

        vm.undo(vm.state.value.entries.first())
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(0L, repo.totalXp())
    }

    @Test
    fun `editing a completion to a harder difficulty raises its xp`() = runTest {
        val easy = quest("a", difficulty = Difficulty.EASY)
        repo.addQuest(easy)
        repo.completeQuest(easy, epochDay = today, result = CompletionResult.COMPLETED)
        val before = repo.totalXp()

        val vm = CompletedViewModel(repo, todayEpochDay = { today })
        vm.startEdit(vm.state.value.entries.first())
        vm.saveEdit(easy.copy(difficulty = Difficulty.EPIC))

        assertTrue("EPIC should out-score EASY", repo.totalXp() > before)
        assertEquals(null, vm.state.value.editing) // dialog closed
    }

    @Test
    fun `re-add clones the quest as a fresh active quest`() = runTest {
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = today, result = CompletionResult.COMPLETED)
        val vm = CompletedViewModel(repo, todayEpochDay = { today })

        vm.readd(vm.state.value.entries.first())

        // Original plus the clone -> two active quests with the same title.
        val active = repo.questOverview(epochDay = today, dayPart = com.questloop.core.model.DayPart.MORNING)
        assertEquals(2, active.count { it.quest.title == "Quest a" })
    }

    @Test
    fun `a double-tap re-add clones the quest only once`() = runTest {
        // Re-add mints a fresh UUID, so a second tap before the first lands is a REAL
        // duplicate (idempotency can't protect it). The inFlight guard must swallow it.
        // StandardTestDispatcher keeps the first guarded coroutine pending while the
        // second tap fires, reproducing the double-tap the Unconfined dispatcher can't.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = today, result = CompletionResult.COMPLETED)
        val vm = CompletedViewModel(repo, todayEpochDay = { today })
        advanceUntilIdle() // run the init load()
        val entry = vm.state.value.entries.first()

        vm.readd(entry) // sets inFlight, queues the clone
        vm.readd(entry) // inFlight still set → must be a no-op
        advanceUntilIdle()

        val active = repo.questOverview(epochDay = today, dayPart = com.questloop.core.model.DayPart.MORNING)
        assertEquals("only one clone despite the double tap", 2, active.count { it.quest.title == "Quest a" })
    }

    @Test
    fun `the Today filter excludes completions from earlier days`() = runTest {
        repo.addQuest(quest("old"))
        repo.completeQuest(quest("old"), epochDay = today - 10, result = CompletionResult.COMPLETED)
        val vm = CompletedViewModel(repo, todayEpochDay = { today })

        vm.setFilter(HistoryFilter.TODAY)
        assertFalse(vm.state.value.entries.any { it.record.questId == "old" })
        vm.setFilter(HistoryFilter.ALL)
        assertTrue(vm.state.value.entries.any { it.record.questId == "old" })
    }

    @Test
    fun `week and month filters use calendar boundaries, not a rolling window`() = runTest {
        // Today's ISO week starts Monday 2026-02-23; the month window starts today (Mar 1).
        val monday = LocalDate.of(2026, 2, 23).toEpochDay()
        repo.addQuest(quest("mon"))
        repo.completeQuest(quest("mon"), epochDay = monday, result = CompletionResult.COMPLETED)
        repo.addQuest(quest("sun"))
        repo.completeQuest(quest("sun"), epochDay = monday - 1, result = CompletionResult.COMPLETED)
        val vm = CompletedViewModel(repo, todayEpochDay = { today })

        vm.setFilter(HistoryFilter.WEEK)
        val week = vm.state.value.entries.map { it.record.questId }
        assertTrue("start of week is inclusive", "mon" in week)
        assertFalse("the Sunday before is outside the week", "sun" in week)

        vm.setFilter(HistoryFilter.MONTH)
        val month = vm.state.value.entries.map { it.record.questId }
        assertFalse("this week but last month -> outside the month window", "mon" in month)
    }

    @Test
    fun `day rollover in an open session moves a completion out of the Today filter`() = runTest {
        var now = today
        repo.addQuest(quest("a"))
        repo.completeQuest(quest("a"), epochDay = now, result = CompletionResult.COMPLETED)
        val vm = CompletedViewModel(repo, todayEpochDay = { now })

        vm.setFilter(HistoryFilter.TODAY)
        assertEquals(1, vm.state.value.entries.size)

        now += 1 // midnight passes while the screen stays open
        vm.load()
        assertTrue(vm.state.value.entries.isEmpty())
    }
}
