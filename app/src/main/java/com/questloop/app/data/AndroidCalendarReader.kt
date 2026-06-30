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
import java.time.LocalDate
import java.time.ZoneId

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

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24 * 60 * MINUTE_MILLIS
    }
}
