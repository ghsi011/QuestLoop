package com.questloop.app.widget

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.BadHabit
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
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

/** Covers the widget's completion menu: loading a quest by id and marking it done. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickCompleteViewModelTest {

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
        override suspend fun getAiConfig(): AiConfig = AiConfig()
        override suspend fun setAiConfig(config: AiConfig) {}
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = ReminderConfig()
        override suspend fun setReminderConfig(config: ReminderConfig) {}
        override suspend fun clear() {}
    }

    private fun quest(id: String) = Quest(
        id = id,
        title = "Quest $id",
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
    )

    private fun quantitativeQuest(id: String, target: Int) = quest(id).copy(
        completionStyle = CompletionStyle.QUANTITATIVE,
        targetCount = target,
        unit = "glasses",
    )

    private fun durationQuest(id: String, minutes: Int) = quest(id).copy(
        completionStyle = CompletionStyle.DURATION,
        estimatedMinutes = minutes,
    )

    private fun subjectiveQuest(id: String) = quest(id).copy(
        completionStyle = CompletionStyle.SUBJECTIVE,
    )

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
    fun `loads the quest title then marks it done`() = runTest {
        repo.addQuest(quest("a"))
        val vm = QuickCompleteViewModel(repo, questId = "a", epochDay = 1)

        assertFalse("loaded", vm.state.value.loading)
        assertEquals("Quest a", vm.state.value.title)

        vm.complete()

        assertTrue("xp was granted", repo.totalXp() > 0)
        assertNotNull("the activity is told to confirm + close", vm.state.value.doneMessage)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `an unknown quest id resolves to not-found`() = runTest {
        val vm = QuickCompleteViewModel(repo, questId = "ghost", epochDay = 1)

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.notFound)
    }

    @Test
    fun `loads a measured quest with its style and resumed progress`() = runTest {
        repo.addQuest(quantitativeQuest("q", target = 8))
        // A prior partial log this interval: the stepper should resume from it.
        repo.completeMeasured(repo.activeQuestById("q")!!, epochDay = 1, value = 3)

        val vm = QuickCompleteViewModel(repo, questId = "q", epochDay = 1)

        assertFalse(vm.state.value.loading)
        assertEquals(CompletionStyle.QUANTITATIVE, vm.state.value.quest?.completionStyle)
        assertEquals("resumes from the logged count", 3, vm.state.value.progress)
    }

    @Test
    fun `logMeasured credits a partial value without marking the quest fully done`() = runTest {
        repo.addQuest(quantitativeQuest("q", target = 8))
        val vm = QuickCompleteViewModel(repo, questId = "q", epochDay = 1)

        vm.logMeasured(2)

        assertNotNull("the activity is told to confirm + close", vm.state.value.doneMessage)
        assertNull(vm.state.value.error)
        assertTrue("partial progress earns proportional XP", repo.totalXp() > 0)
        // A 2-of-8 log is still due — not dismissed like a full completion would be.
        val stillDue = repo.widgetQuickTasks(epochDay = 1, dayPart = DayPart.MORNING).map { it.id }
        assertTrue("a partial log keeps the quest tickable", stillDue.contains("q"))
    }

    @Test
    fun `a duration quest resumes from its logged minutes`() = runTest {
        repo.addQuest(durationQuest("d", minutes = 50))
        repo.completeMeasured(repo.activeQuestById("d")!!, epochDay = 1, value = 20)

        val vm = QuickCompleteViewModel(repo, questId = "d", epochDay = 1)

        assertFalse(vm.state.value.loading)
        assertEquals(CompletionStyle.DURATION, vm.state.value.quest?.completionStyle)
        assertEquals("resumes from the logged minutes", 20, vm.state.value.progress)
    }

    @Test
    fun `logMeasured records the chosen subjective rating`() = runTest {
        repo.addQuest(subjectiveQuest("s"))
        val vm = QuickCompleteViewModel(repo, questId = "s", epochDay = 1)

        assertEquals(CompletionStyle.SUBJECTIVE, vm.state.value.quest?.completionStyle)

        vm.logMeasured(4)

        assertNotNull("the activity is told to confirm + close", vm.state.value.doneMessage)
        assertNull(vm.state.value.error)
        assertTrue("a rated log earns XP", repo.totalXp() > 0)
    }
}
