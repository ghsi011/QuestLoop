package com.questloop.app.ui.review

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class ReviewViewModelTest {

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
        override suspend fun getAiConfig(): AiConfig = AiConfig() // AI off -> deterministic fallback
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
    fun `load produces weekly and monthly reviews with factual summaries`() = runTest {
        repo.addQuest(quest())
        repo.completeQuest(quest(), epochDay = com.questloop.app.ui.AppClock.todayEpochDay(), result = CompletionResult.COMPLETED)
        val vm = ReviewViewModel(repo)
        vm.load()
        val state = vm.state.value
        assertFalse(state.loading)
        assertNotNull(state.weekly)
        assertNotNull(state.monthly)
        assertNotNull(state.weeklySummary)
        assertNotNull(state.monthlySummary)
        // AI is off in the fake prefs, so the AI action is unavailable.
        assertFalse(state.aiAvailable)
    }

    @Test
    fun `load builds weekly and monthly plans and mode toggles`() = runTest {
        repo.addQuest(quest()) // a daily quest -> should populate the forward plan
        val vm = ReviewViewModel(repo)
        vm.load()
        val state = vm.state.value
        assertNotNull(state.weeklyPlan)
        assertNotNull(state.monthlyPlan)
        assertTrue(state.weeklyPlan!!.items.any { it.quest.id == "a" })
        // Default mode is the retrospective; toggling flips to the forward plan.
        assertTrue(state.mode == ReviewMode.REVIEW)
        vm.setMode(ReviewMode.PLAN)
        assertTrue(vm.state.value.mode == ReviewMode.PLAN)
    }

    @Test
    fun `summarize with ai falls back to deterministic text when ai is off`() = runTest {
        val vm = ReviewViewModel(repo)
        vm.load()
        vm.summarizeWithAi()
        val state = vm.state.value
        assertFalse(state.summarizing)
        assertNotNull(state.weeklySummary)
        assertNotNull(state.monthlySummary)
    }

    @Test
    fun `summarize with ai is a no-op before load`() = runTest {
        val vm = ReviewViewModel(repo)
        // No load yet -> weekly/monthly null -> early return, no crash.
        vm.summarizeWithAi()
        assertTrue(vm.state.value.loading)
    }
}
