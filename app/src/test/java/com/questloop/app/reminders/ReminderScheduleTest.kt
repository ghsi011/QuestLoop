package com.questloop.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class ReminderScheduleTest {

    private val utc = ZoneOffset.UTC

    // DST zone: in 2026 clocks jump 02:00 -> 03:00 on Mar 29 (gap) and fall
    // back 03:00 -> 02:00 on Oct 25 (02:00-03:00 occurs twice).
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun localTime(triggerMillis: Long, zone: ZoneId) =
        Instant.ofEpochMilli(triggerMillis).atZone(zone).toLocalTime()

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

    @Test
    fun `spring-forward gap shifts a nonexistent local time forward, never skips the day`() {
        // 00:30 local on the gap day; 02:30 doesn't exist (clocks jump 02:00 -> 03:00).
        val now = millis("2026-03-28T23:30:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 2, minute = 30, zone = berlin)
        // java.time resolves a gap by adding its length: fires at 03:30 local, same day.
        assertEquals(millis("2026-03-29T01:30:00Z"), trigger)
        assertEquals(LocalTime.of(3, 30), localTime(trigger, berlin))
        assertTrue(trigger > now)
    }

    @Test
    fun `fall-back overlap picks the first occurrence of a repeated local time`() {
        // 00:30 local on the overlap day; 02:30 occurs twice (clocks fall back 03:00 -> 02:00).
        val now = millis("2026-10-24T22:30:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 2, minute = 30, zone = berlin)
        // java.time resolves an overlap with the earlier (summer, +02:00) offset.
        assertEquals(millis("2026-10-25T00:30:00Z"), trigger)
        assertEquals(LocalTime.of(2, 30), localTime(trigger, berlin))
        assertTrue(trigger > now)
    }

    @Test
    fun `rolling to tomorrow across spring-forward keeps the local wall time`() {
        // 09:00 local on Mar 28; 08:00 has passed, so tomorrow — a 23-hour day.
        val now = millis("2026-03-28T08:00:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 8, minute = 0, zone = berlin)
        // 08:00 local on Mar 29 is 06:00Z; naive +24h arithmetic would land on 09:00 local.
        assertEquals(millis("2026-03-29T06:00:00Z"), trigger)
        assertEquals(LocalTime.of(8, 0), localTime(trigger, berlin))
    }

    @Test
    fun `rolling to tomorrow across fall-back keeps the local wall time`() {
        // 09:00 local on Oct 24; 08:00 has passed, so tomorrow — a 25-hour day.
        val now = millis("2026-10-24T07:00:00Z")
        val trigger = ReminderSchedule.nextTriggerMillis(now, hour = 8, minute = 0, zone = berlin)
        // 08:00 local on Oct 25 is 07:00Z; naive +24h arithmetic would land on 07:00 local.
        assertEquals(millis("2026-10-25T07:00:00Z"), trigger)
        assertEquals(LocalTime.of(8, 0), localTime(trigger, berlin))
    }
}
