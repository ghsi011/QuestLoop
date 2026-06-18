package com.questloop.app.ui.quests

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestBank
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
class QuestBankViewModelTest {

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
        repo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `catalog is grouped and nothing is added on a fresh list`() = runTest {
        val vm = QuestBankViewModel(repo)
        val state = vm.state.value
        assertTrue(state.groups.isNotEmpty())
        assertTrue(state.addedIds.isEmpty())
    }

    @Test
    fun `adding a bank quest marks it added and shows a toast`() = runTest {
        val vm = QuestBankViewModel(repo)
        val bankQuest = QuestBank.catalog.first()
        vm.add(bankQuest)
        // It's persisted to the active list, which the collector reflects as added.
        assertTrue(repo.activeQuestIds().contains(bankQuest.id))
        val state = vm.state.value
        assertTrue(state.addedIds.contains(bankQuest.id))
        assertNotNull(state.toast)
    }

    @Test
    fun `consume toast clears it`() = runTest {
        val vm = QuestBankViewModel(repo)
        vm.add(QuestBank.catalog.first())
        vm.consumeToast()
        assertNull(vm.state.value.toast)
    }

    @Test
    fun `adding an already-added quest is a no-op`() = runTest {
        val vm = QuestBankViewModel(repo)
        val bankQuest = QuestBank.catalog.first()
        vm.add(bankQuest)
        vm.consumeToast()
        // Second add for the same id should be ignored (already in addedIds).
        vm.add(bankQuest)
        assertNull(vm.state.value.toast)
    }
}
