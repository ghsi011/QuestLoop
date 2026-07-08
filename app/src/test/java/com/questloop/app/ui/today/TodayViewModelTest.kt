package com.questloop.app.ui.today

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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
class TodayViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    /** Minimal in-memory preferences so the repository can build a plan. */
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
        private var checkIn: EnergyCheckIn? = null
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) { this.checkIn = checkIn }
        override suspend fun getCheckIn(): EnergyCheckIn? = checkIn
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

    private fun quest() = Quest(
        id = "a",
        title = "Write the report",
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
    )

    @Test
    fun `setting then clearing the check-in toggles the energy state`() = runTest {
        repo.addQuest(quest())
        val vm = TodayViewModel(repo)

        vm.setCheckIn(1) // rest day
        assertEquals(1, vm.state.value.energy)

        vm.clearCheckIn() // re-tapping the active chip deselects it
        assertNull(vm.state.value.energy)
    }

    @Test
    fun `completing shows a toast and offers undo`() = runTest {
        repo.addQuest(quest())
        val vm = TodayViewModel(repo)

        vm.complete(quest(), CompletionResult.COMPLETED)

        assertTrue("xp was granted", repo.totalXp() > 0)
        val state = vm.state.value
        assertNotNull("a toast is shown", state.toast)
        assertNotNull("undo is offered", state.pendingUndo)
    }

    @Test
    fun `undo restores the prior xp and clears the toast`() = runTest {
        repo.addQuest(quest())
        val vm = TodayViewModel(repo)

        vm.complete(quest(), CompletionResult.COMPLETED)
        assertTrue(repo.totalXp() > 0)

        vm.undo(vm.state.value.pendingUndo!!)

        assertEquals(0L, repo.totalXp())
        assertNull(vm.state.value.toast)
        assertNull(vm.state.value.pendingUndo)
    }

    @Test
    fun `every toast bumps toastId so an identical consecutive message still re-fires`() = runTest {
        // Pins the documented one-shot-event fix: the snackbar LaunchedEffect keys on
        // the monotonic toastId, not the message string, so a second identical toast
        // isn't swallowed (and its Undo lost). A regression to keying on the string
        // would leave toastId flat on the repeat.
        repo.addQuest(quest())
        val vm = TodayViewModel(repo)

        vm.complete(quest(), CompletionResult.COMPLETED)
        val first = vm.state.value
        assertNotNull("a toast is shown", first.toast)
        assertEquals(1, first.toastId)

        // Same quest again — XP is idempotent, but the toast must still re-fire.
        vm.complete(quest(), CompletionResult.COMPLETED)
        val second = vm.state.value
        assertNotNull("the repeat still shows a toast", second.toast)
        assertEquals("toastId bumps even for an identical message", 2, second.toastId)
    }
}
