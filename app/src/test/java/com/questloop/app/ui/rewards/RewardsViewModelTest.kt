package com.questloop.app.ui.rewards

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.generation.AdminFundFactory
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
class RewardsViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    /** Persists the budget cap so load() reflects what setBudgetCap stored. */
    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile())
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(monthlyRewardBudgetCap = value),
            )
        }
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
    fun `init loads an allowance and finishes loading`() = runTest {
        val vm = RewardsViewModel(repo)
        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.allowance)
    }

    @Test
    fun `saving a budget updates the cap and emits a saved message`() = runTest {
        val vm = RewardsViewModel(repo)
        vm.setBudgetCap(120.0)
        val state = vm.state.value
        assertEquals(120.0, state.budgetCap, 0.0001)
        assertNotNull(state.savedMessage)
        assertTrue(state.messageId > 0)
    }

    @Test
    fun `clearing the budget reports it cleared`() = runTest {
        val vm = RewardsViewModel(repo)
        vm.setBudgetCap(0.0)
        assertEquals("Budget cleared.", vm.state.value.savedMessage)
    }

    @Test
    fun `the reward-fund flow surfaces open-pot then advances when marked done`() = runTest {
        val vm = RewardsViewModel(repo)
        vm.setBudgetCap(50.0)
        assertTrue(vm.state.value.fundBudgetSet)
        val openPot = vm.state.value.fundSteps.firstOrNull { it.id == AdminFundFactory.OPEN_POT_ID }
        assertNotNull("open-pot step is offered once a budget is set", openPot)

        vm.markFundStepDone(openPot!!)
        val state = vm.state.value
        assertTrue("pot now shows opened", state.fundPotOpened)
        assertTrue(state.fundSteps.none { it.id == AdminFundFactory.OPEN_POT_ID })
        assertTrue(state.fundSteps.any { it.id == AdminFundFactory.FUND_MONTH_ID })
    }

    @Test
    fun `consuming the saved message clears it`() = runTest {
        val vm = RewardsViewModel(repo)
        vm.setBudgetCap(50.0)
        assertNotNull(vm.state.value.savedMessage)
        vm.consumeSavedMessage()
        assertNull(vm.state.value.savedMessage)
    }

    @Test
    fun `a failed load finishes loading and surfaces an error message`() = runTest {
        db.close() // Make the store fail: the allowance query throws.
        val vm = RewardsViewModel(repo)
        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.savedMessage)
        assertTrue(state.messageId > 0)
    }
}
