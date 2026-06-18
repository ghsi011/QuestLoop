package com.questloop.app.ui.habits

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HabitsViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    /** Persists habit/bad-habit/goal lists so add/remove are observable via reload. */
    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile())
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
        override suspend fun setStreakGraceDays(value: Int) {}
        override suspend fun setSensitiveOptIn(value: Boolean) {}
        override suspend fun setHabits(habits: List<Habit>) {
            state.value = state.value.copy(habits = habits)
        }
        override suspend fun setBadHabits(badHabits: List<BadHabit>) {
            state.value = state.value.copy(badHabits = badHabits)
        }
        override suspend fun setGoals(goals: List<Goal>) {
            state.value = state.value.copy(goals = goals)
        }
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {}
        override suspend fun getCheckIn(): EnergyCheckIn? = null
        override suspend fun getAiConfig(): AiConfig = AiConfig()
        override suspend fun setAiConfig(config: AiConfig) {}
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = ReminderConfig()
        override suspend fun setReminderConfig(config: ReminderConfig) {}
        override suspend fun clear() { state.value = UserProfile() }
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
    fun `init loads empty lists`() = runTest {
        val vm = HabitsViewModel(repo)
        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.habits.isEmpty())
        assertTrue(state.badHabits.isEmpty())
        assertTrue(state.goals.isEmpty())
    }

    @Test
    fun `adding a habit appears in state`() = runTest {
        val vm = HabitsViewModel(repo)
        vm.addHabit("Stretch", QuestCategory.HEALTH, targetPerWeek = 7)
        val habits = vm.state.value.habits
        assertEquals(1, habits.size)
        assertEquals("Stretch", habits.first().title)
    }

    @Test
    fun `a blank habit title is ignored`() = runTest {
        val vm = HabitsViewModel(repo)
        vm.addHabit("   ", QuestCategory.HEALTH, targetPerWeek = 3)
        assertTrue(vm.state.value.habits.isEmpty())
    }

    @Test
    fun `removing a habit takes it out of state`() = runTest {
        val vm = HabitsViewModel(repo)
        vm.addHabit("Stretch", QuestCategory.HEALTH, targetPerWeek = 7)
        val id = vm.state.value.habits.first().id
        vm.removeHabit(id)
        assertTrue(vm.state.value.habits.isEmpty())
    }

    @Test
    fun `adding a bad habit appears in state`() = runTest {
        val vm = HabitsViewModel(repo)
        vm.addBadHabit("Doomscrolling", dailyLimit = 1)
        assertEquals(1, vm.state.value.badHabits.size)
        assertEquals("Doomscrolling", vm.state.value.badHabits.first().title)
    }

    @Test
    fun `adding a goal appears in state and can be removed`() = runTest {
        val vm = HabitsViewModel(repo)
        vm.addGoal("Learn guitar", QuestCategory.PERSONAL_GROWTH)
        assertEquals(1, vm.state.value.goals.size)
        val id = vm.state.value.goals.first().id
        vm.removeGoal(id)
        assertTrue(vm.state.value.goals.isEmpty())
    }
}
