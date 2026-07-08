package com.questloop.app.ui.quests

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.BadHabit
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestsViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    private class FakePrefs : ProfilePreferences {
        /** When set, check-in reads throw — simulates a store failure mid-refresh. */
        var failGetCheckIn = false
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
        override suspend fun getCheckIn(): EnergyCheckIn? =
            if (failGetCheckIn) throw RuntimeException("check-in store failed") else null
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
        repo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun quest(id: String = "a", title: String = "Write the report") = Quest(
        id = id,
        title = title,
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
    )

    @Test
    fun `backlog reflects an active quest`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(1, state.total)
        assertTrue(state.groups.flatMap { it.items }.any { it.quest.id == "a" })
    }

    @Test
    fun `completing grants xp, offers undo, and drops the quest from the backlog`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        vm.complete(quest())
        assertTrue(repo.totalXp() > 0)
        val state = vm.state.value
        assertNotNull(state.toast)
        assertNotNull(state.pendingUndo)
        // Done for today -> no longer in any group.
        assertTrue(state.groups.flatMap { it.items }.none { it.quest.id == "a" })
    }

    @Test
    fun `undo restores the quest and clears the toast`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        vm.complete(quest())
        assertTrue(repo.totalXp() > 0)
        vm.undo(vm.state.value.pendingUndo!!)
        assertEquals(0L, repo.totalXp())
        assertNull(vm.state.value.toast)
        assertNull(vm.state.value.pendingUndo)
        assertTrue(vm.state.value.groups.flatMap { it.items }.any { it.quest.id == "a" })
    }

    @Test
    fun `skip records a completion and offers undo`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        vm.skip(quest())
        assertNotNull(vm.state.value.toast)
        assertNotNull(vm.state.value.pendingUndo)
    }

    @Test
    fun `delete archives the quest and removes it from the backlog`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        vm.delete(quest())
        assertFalse(repo.activeQuestIds().contains("a"))
        assertTrue(vm.state.value.groups.flatMap { it.items }.none { it.quest.id == "a" })
    }

    @Test
    fun `consume toast clears the toast and undo`() = runTest {
        repo.addQuest(quest())
        val vm = QuestsViewModel(repo)
        vm.complete(quest())
        vm.consumeToast()
        assertNull(vm.state.value.toast)
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun `a failed refresh surfaces an error and keeps the backlog subscription alive`() = runTest {
        val prefs = FakePrefs().apply { failGetCheckIn = true }
        val flaky = QuestRepository(db.questDao(), db.completionDao(), prefs)
        val vm = QuestsViewModel(flaky)
        // The first refresh fails: the spinner is released and the failure surfaces.
        assertFalse(vm.state.value.loading)
        assertNotNull(vm.state.value.toast)
        // The collector survives, so the next change still refreshes the backlog.
        prefs.failGetCheckIn = false
        flaky.addQuest(quest())
        assertEquals(1, vm.state.value.total)
        assertTrue(vm.state.value.groups.flatMap { it.items }.any { it.quest.id == "a" })
    }
}
