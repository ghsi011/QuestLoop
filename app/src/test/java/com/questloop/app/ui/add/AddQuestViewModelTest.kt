package com.questloop.app.ui.add

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.CalendarEventSummary
import com.questloop.app.data.CalendarReader
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
class AddQuestViewModelTest {

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
        override suspend fun getAiConfig(): AiConfig = AiConfig() // AI off -> deterministic suggestions
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
        // The draft is cached in a process-scoped companion var (so it survives
        // navigating away from the screen) — reset it so tests don't leak state.
        AddQuestViewModel.resetDraftCache()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `generate populates suggestions for review without adding them`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.generate("Email landlord\nCall plumber")

        assertEquals(2, vm.state.value.suggestions.size)
        // Nothing persisted yet — the user reviews first.
        assertTrue(repo.activeQuestIds().isEmpty())
    }

    @Test
    fun `editing then accepting a suggestion persists the edited quest`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.generate("Email landlord")
        val suggestion = vm.state.value.suggestions.single()
        vm.updateSuggestion(suggestion.copy(title = "Email the landlord today"))
        vm.acceptSuggestion(suggestion.id)

        assertTrue(vm.state.value.suggestions.isEmpty())
        val saved = repo.questOverview(epochDay = 1, dayPart = com.questloop.core.model.DayPart.MORNING)
        assertTrue(saved.any { it.quest.title == "Email the landlord today" })
    }

    @Test
    fun `add all persists every suggestion`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.generate("Email landlord\nCall plumber")
        vm.acceptAll()

        assertTrue(vm.state.value.suggestions.isEmpty())
        assertEquals(2, repo.activeQuestIds().size)
    }

    @Test
    fun `a failed generate clears the spinner and reports it`() = runTest {
        val vm = AddQuestViewModel(repo)
        // Drop the quests table so the dedup read inside suggestQuests throws a real
        // SQLiteException. NOT db.close(): closing cancels Room's query scope, and
        // that CancellationException is cooperative cancellation the ViewModel
        // correctly rethrows — so it would never reach the error handler.
        db.openHelper.writableDatabase.execSQL("DROP TABLE quests")

        vm.generate("Email landlord")

        assertFalse(vm.state.value.generating)
        assertEquals("Something went wrong — try again.", vm.state.value.message)
    }

    @Test
    fun `a failed accept keeps the suggestion and re-enables the add buttons`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.generate("Email landlord")
        // Drop the quests table so persisting throws a real SQLiteException. NOT
        // db.close(): closing cancels Room's query scope, and that
        // CancellationException is cooperative cancellation the ViewModel correctly
        // rethrows — so it would never reach the error handler.
        db.openHelper.writableDatabase.execSQL("DROP TABLE quests")

        vm.acceptSuggestion(vm.state.value.suggestions.single().id)

        val state = vm.state.value
        assertFalse(state.saving)
        assertEquals(1, state.suggestions.size) // nothing silently dropped
        assertNotNull(state.message)
    }

    @Test
    fun `setting and clearing a deadline updates the draft`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.setDeadline(100L)
        assertEquals(100L, vm.draft.value.deadlineEpochDay)
        vm.setDeadline(null)
        assertEquals(null, vm.draft.value.deadlineEpochDay)
    }

    @Test
    fun `a manually set deadline is persisted on the saved quest`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.updateDraft { it.copy(title = "Renew passport") }
        vm.setDeadline(200L)
        vm.addQuest {}

        val saved = repo.questOverview(epochDay = 1, dayPart = com.questloop.core.model.DayPart.MORNING)
            .first { it.quest.title == "Renew passport" }
        assertEquals(200L, saved.quest.deadlineEpochDay)
    }

    @Test
    fun `over-completion flag saves only for measured styles`() = runTest {
        val vm = AddQuestViewModel(repo)
        // Quantitative + flag on -> persisted.
        vm.updateDraft {
            it.copy(
                title = "Swim",
                completionStyle = com.questloop.core.model.CompletionStyle.QUANTITATIVE,
                allowOverCompletion = true,
            )
        }
        vm.addQuest {}
        val swim = repo.questOverview(epochDay = 1, dayPart = com.questloop.core.model.DayPart.MORNING)
            .first { it.quest.title == "Swim" }
        assertTrue(swim.quest.allowOverCompletion)

        // Binary + flag on -> ignored (over-completion is meaningless there).
        vm.updateDraft {
            QuestDraft(
                title = "Call mum",
                completionStyle = com.questloop.core.model.CompletionStyle.BINARY,
                allowOverCompletion = true,
            )
        }
        vm.addQuest {}
        val call = repo.questOverview(epochDay = 1, dayPart = com.questloop.core.model.DayPart.MORNING)
            .first { it.quest.title == "Call mum" }
        assertFalse(call.quest.allowOverCompletion)
    }

    @Test
    fun `a calendar-picked deadline and its tag are both persisted on the saved quest`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.pickDeadlineFromEvent(CalendarEventSummary(id = "e1", title = "Dentist", epochDay = 300L))
        vm.addQuest {}

        val saved = repo.questOverview(epochDay = 1, dayPart = com.questloop.core.model.DayPart.MORNING)
            .first { it.quest.title == "Dentist" }
        assertEquals(300L, saved.quest.deadlineEpochDay)
        assertEquals(listOf("calendar"), saved.quest.tags)
    }

    @Test
    fun `loading calendar events populates state from the reader`() = runTest {
        val event = CalendarEventSummary(id = "e1", title = "Dentist", epochDay = 50L)
        val reader = object : CalendarReader {
            override suspend fun freeMinutesToday(): Int? = null
            override suspend fun upcomingEvents(daysAhead: Int): List<CalendarEventSummary> = listOf(event)
        }
        val calRepo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(), calendarReader = reader)
        val vm = AddQuestViewModel(calRepo)

        vm.loadCalendarEvents()

        assertFalse(vm.state.value.loadingCalendarEvents)
        assertEquals(listOf(event), vm.state.value.calendarEvents)
    }

    @Test
    fun `picking a deadline from an event sets the date, tags the quest, and fills a blank title`() = runTest {
        val vm = AddQuestViewModel(repo)
        val event = CalendarEventSummary(id = "e1", title = "Dentist", epochDay = 50L)

        vm.pickDeadlineFromEvent(event)

        val draft = vm.draft.value
        assertEquals(50L, draft.deadlineEpochDay)
        assertEquals("Dentist", draft.title) // blank title -> pre-filled
        assertEquals(listOf("calendar"), draft.tags)
    }

    @Test
    fun `picking a deadline from an event never overwrites an existing title`() = runTest {
        val vm = AddQuestViewModel(repo)
        vm.updateDraft { it.copy(title = "My own title") }
        val event = CalendarEventSummary(id = "e1", title = "Dentist", epochDay = 50L)

        vm.pickDeadlineFromEvent(event)

        assertEquals("My own title", vm.draft.value.title)
        assertEquals(50L, vm.draft.value.deadlineEpochDay)
    }

    @Test
    fun `picking from an event twice does not duplicate the calendar tag`() = runTest {
        val vm = AddQuestViewModel(repo)
        val event = CalendarEventSummary(id = "e1", title = "Dentist", epochDay = 50L)
        vm.pickDeadlineFromEvent(event)
        vm.pickDeadlineFromEvent(event.copy(epochDay = 60L))

        assertEquals(listOf("calendar"), vm.draft.value.tags)
        assertEquals(60L, vm.draft.value.deadlineEpochDay)
    }
}
