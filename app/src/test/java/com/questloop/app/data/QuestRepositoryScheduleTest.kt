package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.BadHabit
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Repository behaviour for scheduled quests (set times, anchor days, occurrence
 * limits, reminder gating): the app-side complement to `:core`'s QuestScheduleTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryScheduleTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(
            UserProfile(preferences = UserPreferences(firstDayOfWeek = DayOfWeek.MONDAY)),
        )
        override val profile: Flow<UserProfile> = state
        override suspend fun setFirstDayOfWeek(day: DayOfWeek) {}
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
        override suspend fun setStreakGraceDays(value: Int) {}
        override suspend fun setSensitiveOptIn(value: Boolean) {}
        override suspend fun setCalendarBudgetEnabled(value: Boolean) {}
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
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs())
    }

    @After
    fun tearDown() = db.close()

    private fun quest(
        id: String = "q-sched",
        frequency: QuestFrequency = QuestFrequency.DAILY,
        style: CompletionStyle = CompletionStyle.BINARY,
        times: List<Int> = emptyList(),
        dayOfMonth: Int? = null,
        total: Int? = null,
        reminders: Boolean = false,
        targetCount: Int? = null,
    ) = Quest(
        id = id,
        title = "Scheduled quest",
        category = QuestCategory.HEALTH,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        completionStyle = style,
        targetCount = targetCount,
        scheduledTimes = times,
        scheduledDayOfMonth = dayOfMonth,
        totalOccurrences = total,
        remindersEnabled = reminders,
    )

    private suspend fun status(epochDay: Long, id: String = "q-sched") =
        repo.questOverview(epochDay, DayPart.MORNING).first { it.quest.id == id }

    @Test
    fun `occurrence-limited quest retires after its last completion`() = runTest {
        val day1 = LocalDate.of(2024, 3, 4).toEpochDay()
        repo.addQuest(quest(total = 2))
        val stored = repo.activeQuestById("q-sched")!!

        repo.completeQuest(stored, day1, CompletionResult.COMPLETED)
        val afterOne = status(day1 + 1)
        assertTrue(afterOne.dueToday)
        assertEquals(1, afterOne.completedOccurrences)

        repo.completeQuest(stored, day1 + 1, CompletionResult.COMPLETED)
        val afterTwo = status(day1 + 2)
        assertFalse(afterTwo.dueToday)
        assertTrue(afterTwo.done)
        assertEquals(2, afterTwo.completedOccurrences)

        // Gone from the plan too, like a finished one-off.
        val plan = repo.todayPlan(day1 + 2, DayPart.MORNING)
        assertTrue(plan.quests.none { it.quest.id == "q-sched" })
    }

    @Test
    fun `two completions inside one month are a single occurrence`() = runTest {
        // A monthly quest completed early AND on the anchor day must burn ONE month
        // of a bounded run, not two (records are keyed per day for binary quests).
        repo.addQuest(quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 5, total = 12))
        val stored = repo.activeQuestById("q-sched")!!
        val jan5 = LocalDate.of(2024, 1, 5).toEpochDay()
        val jan20 = LocalDate.of(2024, 1, 20).toEpochDay()
        repo.completeQuest(stored, jan5, CompletionResult.COMPLETED)
        repo.completeQuest(stored, jan20, CompletionResult.COMPLETED)
        assertEquals(1, status(jan20 + 1).completedOccurrences)
        val feb6 = LocalDate.of(2024, 2, 6).toEpochDay()
        repo.completeQuest(stored, feb6, CompletionResult.COMPLETED)
        assertEquals(2, status(feb6 + 1).completedOccurrences)
    }

    @Test
    fun `skips and partials never advance the occurrence count`() = runTest {
        val day = LocalDate.of(2024, 3, 4).toEpochDay()
        repo.addQuest(quest(total = 2))
        val stored = repo.activeQuestById("q-sched")!!
        repo.completeQuest(stored, day, CompletionResult.SKIPPED)
        repo.completeQuest(stored, day + 1, CompletionResult.PARTIAL, fraction = 0.5)
        assertEquals(0, status(day + 2).completedOccurrences)
        assertTrue(status(day + 2).dueToday)
    }

    @Test
    fun `anchored monthly quest is due from its anchor day until completed`() = runTest {
        repo.addQuest(quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 5))
        val stored = repo.activeQuestById("q-sched")!!
        val jan3 = LocalDate.of(2024, 1, 3).toEpochDay()
        val jan5 = LocalDate.of(2024, 1, 5).toEpochDay()
        val jan20 = LocalDate.of(2024, 1, 20).toEpochDay()
        val feb5 = LocalDate.of(2024, 2, 5).toEpochDay()

        assertFalse(status(jan3).dueToday)
        assertTrue(status(jan5).dueToday)
        assertTrue(status(jan20).dueToday) // missed, still owed
        repo.completeQuest(stored, jan20, CompletionResult.COMPLETED)
        assertFalse(status(jan20 + 1).dueToday) // paid this month
        assertTrue(status(feb5).dueToday) // next month's anchor
    }

    @Test
    fun `multi-time binary quest is stored as a per-slot count`() = runTest {
        repo.addQuest(quest(times = listOf(20 * 60, 8 * 60)))
        val stored = repo.activeQuestById("q-sched")!!
        assertEquals(CompletionStyle.QUANTITATIVE, stored.completionStyle)
        assertEquals(2, stored.targetCount)
        assertEquals(listOf(8 * 60, 20 * 60), stored.scheduledTimes)
    }

    @Test
    fun `reminderQuests includes only eligible quests`() = runTest {
        val day = LocalDate.of(2024, 3, 4).toEpochDay()
        repo.addQuest(quest(id = "with-reminder", times = listOf(8 * 60), reminders = true))
        repo.addQuest(quest(id = "no-reminder", times = listOf(8 * 60), reminders = false))
        repo.addQuest(quest(id = "no-times", reminders = true))
        repo.addQuest(quest(id = "used-up", times = listOf(8 * 60), reminders = true, total = 1))
        repo.completeQuest(repo.activeQuestById("used-up")!!, day, CompletionResult.COMPLETED)

        assertEquals(listOf("with-reminder"), repo.reminderQuests().map { it.id })
    }

    @Test
    fun `completeFromReminder logs one slot per tap and stops when done`() = runTest {
        val day = LocalDate.of(2024, 3, 4).toEpochDay()
        repo.addQuest(quest(times = listOf(8 * 60, 20 * 60), reminders = true))

        assertNotNull(repo.reminderDueQuest("q-sched", day))
        assertTrue(repo.completeFromReminder("q-sched", day)) // morning dose
        // Still due for the evening slot after the first log.
        assertNotNull(repo.reminderDueQuest("q-sched", day))
        assertTrue(repo.completeFromReminder("q-sched", day)) // evening dose
        assertNull(repo.reminderDueQuest("q-sched", day)) // done for the day
        assertFalse(repo.completeFromReminder("q-sched", day)) // nothing left to credit
        assertTrue(status(day).done)
    }

    @Test
    fun `a duplicated mark-done delivery credits only one unit`() = runTest {
        val day = LocalDate.of(2024, 3, 4).toEpochDay()
        repo.addQuest(quest(times = listOf(8 * 60, 20 * 60), reminders = true))
        val morning = repo.reminderDueQuest("q-sched", day)!!.nextCount
        assertEquals(1, morning)
        assertTrue(repo.completeFromReminder("q-sched", day, morning))
        // The double-tap's second broadcast carries the same expected count: a
        // successful no-op, not a second dose.
        assertTrue(repo.completeFromReminder("q-sched", day, morning))
        assertEquals(1, repo.todayProgress(day)["q-sched"])
        // The evening slot still credits normally.
        val evening = repo.reminderDueQuest("q-sched", day)!!.nextCount
        assertEquals(2, evening)
        assertTrue(repo.completeFromReminder("q-sched", day, evening))
        assertTrue(status(day).done)
    }

    @Test
    fun `reminderDueQuest respects the anchor day`() = runTest {
        repo.addQuest(
            quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 5, times = listOf(9 * 60), reminders = true),
        )
        val jan3 = LocalDate.of(2024, 1, 3).toEpochDay()
        val jan5 = LocalDate.of(2024, 1, 5).toEpochDay()
        assertNull(repo.reminderDueQuest("q-sched", jan3))
        assertNotNull(repo.reminderDueQuest("q-sched", jan5))
    }
}
