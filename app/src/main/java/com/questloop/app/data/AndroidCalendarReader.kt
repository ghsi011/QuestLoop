package com.questloop.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.questloop.core.calendar.DayWindow
import com.questloop.core.calendar.FreeBusyCalculator
import com.questloop.core.calendar.Interval
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads today's busy blocks from the device calendar(s) via `CalendarContract`
 * and turns them into the free time left today (SPEC §10). Off unless the user
 * opted in *and* granted `READ_CALENDAR`; otherwise returns null so the planner
 * imposes no calendar budget. Read-only and on-device — nothing leaves the phone.
 */
class AndroidCalendarReader(
    private val context: Context,
    private val prefs: ProfilePreferences,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis,
) : CalendarReader {

    override suspend fun freeMinutesToday(): Int? {
        if (!prefs.profile.first().preferences.calendarBudgetEnabled) return null
        if (!hasPermission()) return null

        val dayStartMillis = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEndMillis = dayStartMillis + DAY_MILLIS
        val busy = queryBusy(dayStartMillis, dayEndMillis)

        val nowMinute = ((now() - dayStartMillis) / MINUTE_MILLIS).toInt()
        return FreeBusyCalculator.freeMinutes(busy, DayWindow.remainingFrom(nowMinute))
    }

    /**
     * Upcoming events for the deadline picker. Independent of [ProfilePreferences]'
     * `calendarBudgetEnabled` — granting `READ_CALENDAR` (via the Settings switch)
     * is enough; the user need not also opt into calendar-based budgeting just to
     * pick a deadline from an event.
     */
    override suspend fun upcomingEvents(daysAhead: Int): List<CalendarEventSummary> {
        if (!hasPermission()) return emptyList()
        val from = now()
        val to = from + daysAhead.coerceAtLeast(1) * DAY_MILLIS
        return queryEvents(from, to)
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Timed (non all-day) instances overlapping today, as minute-of-day intervals. */
    private fun queryBusy(dayStartMillis: Long, dayEndMillis: Long): List<Interval> {
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val intervals = mutableListOf<Interval>()
        val cursor = CalendarContract.Instances.query(
            context.contentResolver, projection, dayStartMillis, dayEndMillis,
        ) ?: return emptyList()
        cursor.use { c ->
            while (c.moveToNext()) {
                // All-day events aren't time blocks, so they don't eat into the budget.
                if (c.getInt(2) == 1) continue
                val begin = c.getLong(0).coerceAtLeast(dayStartMillis)
                val end = c.getLong(1).coerceAtMost(dayEndMillis)
                val startMin = ((begin - dayStartMillis) / MINUTE_MILLIS).toInt()
                val endMin = ((end - dayStartMillis) / MINUTE_MILLIS).toInt()
                if (endMin > startMin) intervals += Interval(startMin, endMin)
            }
        }
        return intervals
    }

    /**
     * Events (timed or all-day) overlapping `[from, to)`, mapped to the calendar
     * day they fall on. A recurring series yields one row per instance — by design,
     * so e.g. "Team standup" tomorrow and next week both show as separate pick
     * targets. `Instances.query`'s cursor order isn't guaranteed chronological, so
     * every matching row is collected and sorted *before* capping the list — a
     * naive cap during iteration could keep an out-of-order later instance over a
     * genuinely sooner one.
     */
    private fun queryEvents(from: Long, to: Long): List<CalendarEventSummary> {
        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val events = mutableListOf<CalendarEventSummary>()
        val cursor = CalendarContract.Instances.query(context.contentResolver, projection, from, to)
            ?: return emptyList()
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Untitled event"
                val begin = c.getLong(2)
                // All-day events are stored in UTC regardless of the device's zone
                // (a documented CalendarContract quirk); timed events use the
                // instant's real local date.
                val isAllDay = c.getInt(3) == 1
                val day = if (isAllDay) {
                    Instant.ofEpochMilli(begin).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                } else {
                    Instant.ofEpochMilli(begin).atZone(zone).toLocalDate().toEpochDay()
                }
                events += CalendarEventSummary(id = id, title = title, epochDay = day)
            }
        }
        return events.sortedBy { it.epochDay }.take(MAX_EVENTS)
    }

    private companion object {
        const val MAX_EVENTS = 50
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
