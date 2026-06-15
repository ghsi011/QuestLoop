package com.questloop.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderScheduleTest {

    private val utc = ZoneOffset.UTC

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `returns today when the time is still ahead`() {
        val now = millis("2026-06-15T06:00:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 8, minute = 0, zone = utc)
        assertEquals(millis("2026-06-15T08:00:00Z"), trigger)
    }

    @Test
    fun `rolls to tomorrow when the time has passed`() {
        val now = millis("2026-06-15T09:00:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 8, minute = 0, zone = utc)
        assertEquals(millis("2026-06-16T08:00:00Z"), trigger)
    }

    @Test
    fun `exactly now rolls to tomorrow (must be strictly ahead)`() {
        val now = millis("2026-06-15T08:00:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 8, minute = 0, zone = utc)
        assertEquals(millis("2026-06-16T08:00:00Z"), trigger)
    }

    @Test
    fun `trigger is always in the future`() {
        val now = millis("2026-06-15T23:59:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 20, minute = 30, zone = utc)
        assertTrue(trigger > now)
    }
}
