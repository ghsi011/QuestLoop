package com.questloop.app.ui.achievements

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
class AchievementsViewModelTest {

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

    private fun quest() = Quest(
        id = "a",
        title = "Write the report",
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
    )

    @Test
    fun `loads all achievements with none unlocked on a fresh ledger`() = runTest {
        val vm = AchievementsViewModel(repo)
        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.items.isNotEmpty())
        assertEquals(0, state.unlockedCount)
    }

    @Test
    fun `first completion shows up as an unlocked achievement`() = runTest {
        repo.addQuest(quest())
        repo.completeQuest(quest(), epochDay = 1, result = CompletionResult.COMPLETED)
        val vm = AchievementsViewModel(repo)
        val state = vm.state.value
        assertTrue(state.unlockedCount >= 1)
        assertTrue(state.items.any { it.achievement.id == "first_steps" && it.unlocked })
    }
}
