package com.questloop.app.reminders

import java.time.Instant
import java.time.ZoneId

/**
 * Pure time math for daily reminders: given "now" and a target hour/minute,
 * returns the next epoch-millis at which that time occurs (today if still ahead,
 * otherwise tomorrow). Kept separate from Android so it's unit-testable.
 */
object ReminderSchedule {
    fun nextTriggerMillis(
        nowEpochMillis: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val now = Instant.ofEpochMilli(nowEpochMillis)
        val date = now.atZone(zone).toLocalDate()
        var candidate = date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone)
        if (!candidate.toInstant().isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }
}
