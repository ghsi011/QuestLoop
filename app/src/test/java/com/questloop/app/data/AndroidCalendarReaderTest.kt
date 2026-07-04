package com.questloop.app.data

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.provider.CalendarContract
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Pins [AndroidCalendarReader]'s threading and mapping: `CalendarContract` queries
 * are blocking cross-process IPC and the callers launch on the main dispatcher
 * (Today's plan, the Add-quest deadline picker), so both entry points must serve
 * the provider query off the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCalendarReaderTest {

    /** Fake calendar provider; Robolectric instantiates it reflectively, so not private. */
    class FakeCalendarProvider : ContentProvider() {
        /** Rows to return, in the projection order the reader asks for. */
        @Volatile
        var rows: List<Array<Any>> = emptyList()

        /** The thread the query was served on — the fix under test. */
        @Volatile
        var queryThread: Thread? = null

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            queryThread = Thread.currentThread()
            return MatrixCursor(projection.orEmpty()).apply { rows.forEach { addRow(it) } }
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    /** Minimal prefs; only the calendar-budget opt-in matters here. */
    private class FakePrefs(calendarBudget: Boolean) : ProfilePreferences {
        private val state = MutableStateFlow(
            UserProfile(preferences = UserPreferences(calendarBudgetEnabled = calendarBudget)),
        )
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

    private lateinit var provider: FakeCalendarProvider

    @Before
    fun setUp() {
        provider = Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.READ_CALENDAR)
    }

    private fun reader(calendarBudget: Boolean = true, nowMillis: Long) = AndroidCalendarReader(
        context = RuntimeEnvironment.getApplication(),
        prefs = FakePrefs(calendarBudget),
        zone = ZoneOffset.UTC,
        now = { nowMillis },
    )

    @Test
    fun `upcomingEvents serves the provider query off the main thread and maps rows`() = runTest {
        val base = 1_750_000_000_000L
        val sooner = base + DAY_MILLIS
        val later = base + 3 * DAY_MILLIS
        // queryEvents projection order (_ID, TITLE, BEGIN, ALL_DAY), deliberately unsorted.
        provider.rows = listOf(
            arrayOf<Any>("e2", "Dentist", later, 0),
            arrayOf<Any>("e1", "Team standup", sooner, 0),
        )

        val events = reader(nowMillis = base).upcomingEvents(daysAhead = 7)

        assertNotNull(provider.queryThread)
        assertNotEquals(Looper.getMainLooper().thread, provider.queryThread)
        assertEquals(listOf("e1", "e2"), events.map { it.id })
        assertEquals("Team standup", events.first().title)
        assertEquals(
            Instant.ofEpochMilli(sooner).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay(),
            events.first().epochDay,
        )
    }

    @Test
    fun `freeMinutesToday subtracts busy time off the main thread`() = runTest {
        val dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // queryBusy projection order (BEGIN, END, ALL_DAY): a 12:00–13:00 meeting,
        // plus an all-day row that must not count as busy time.
        provider.rows = listOf(
            arrayOf<Any>(dayStart + 720 * MINUTE_MILLIS, dayStart + 780 * MINUTE_MILLIS, 0),
            arrayOf<Any>(dayStart, dayStart + DAY_MILLIS, 1),
        )

        // "Now" is 10:00, so the remaining window is 10:00–22:00 = 720 min, minus the meeting.
        val free = reader(nowMillis = dayStart + 600 * MINUTE_MILLIS).freeMinutesToday()

        assertNotNull(provider.queryThread)
        assertNotEquals(Looper.getMainLooper().thread, provider.queryThread)
        assertEquals(660, free)
    }

    @Test
    fun `freeMinutesToday stays null without the calendar-budget opt-in`() = runTest {
        val free = reader(calendarBudget = false, nowMillis = 0L).freeMinutesToday()

        assertNull(free)
        assertNull(provider.queryThread)
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
