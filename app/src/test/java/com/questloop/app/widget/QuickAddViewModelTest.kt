package com.questloop.app.widget

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.app.data.toModel
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
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

/**
 * Covers the widget's no-review quick-add path: text in, one one-off quest saved,
 * plus the guards (blank input, onboarding credit). AI is off here, so the
 * deterministic fallback stands in for the model — the "force ONE_OFF" of a
 * non-one-off suggestion is covered end-to-end in QuestRepositoryOpenAiTest.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickAddViewModelTest {

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
        private var checkIn: EnergyCheckIn? = null
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) { this.checkIn = checkIn }
        override suspend fun getCheckIn(): EnergyCheckIn? = checkIn
        override suspend fun getAiConfig(): AiConfig = AiConfig() // AI off → fallback
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
    fun `submitting text adds one one-off quest and signals success`() = runTest {
        val vm = QuickAddViewModel(repo)
        vm.onInputChange("Book a dentist appointment")

        vm.submit()

        val saved = db.questDao().getActive().map { it.toModel() }
        assertEquals("exactly one quest is added, no review", 1, saved.size)
        assertEquals(QuestFrequency.ONE_OFF, saved.single().frequency)
        assertEquals("Book a dentist appointment", saved.single().title)
        assertEquals("the activity is told to confirm + close", "Book a dentist appointment", vm.state.value.addedTitle)
        assertNull(vm.state.value.error)
        assertTrue(!vm.state.value.submitting)
    }

    @Test
    fun `blank input does nothing`() = runTest {
        val vm = QuickAddViewModel(repo)
        vm.onInputChange("   ")

        vm.submit()

        assertTrue("nothing persisted", db.questDao().getActive().isEmpty())
        assertNull(vm.state.value.addedTitle)
    }
}
