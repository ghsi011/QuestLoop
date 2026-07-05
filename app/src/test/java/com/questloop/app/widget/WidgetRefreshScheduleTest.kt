package com.questloop.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pins the widget's refresh boundaries to what it renders — the epoch day plus
 * the DayPart (morning < 12, midday < 17, evening) — so the home-screen glance
 * flips to the new day/day-part without the app being opened.
 */
class WidgetRefreshScheduleTest {

    private val utc = ZoneOffset.UTC

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `morning rolls to the midday boundary`() {
        val now = millis("2026-06-15T08:30:00Z")
        assertEquals(millis("2026-06-15T12:00:00Z"), WidgetRefreshSchedule.nextBoundaryMillis(now, utc))
    }

    @Test
    fun `midday rolls to the evening boundary`() {
        val now = millis("2026-06-15T14:00:00Z")
        assertEquals(millis("2026-06-15T17:00:00Z"), WidgetRefreshSchedule.nextBoundaryMillis(now, utc))
    }

    @Test
    fun `evening rolls to midnight, the next epoch day`() {
        val now = millis("2026-06-15T20:00:00Z")
        val trigger = WidgetRefreshSchedule.nextBoundaryMillis(now, utc)
        assertEquals(millis("2026-06-16T00:00:00Z"), trigger)
        assertEquals(
            Instant.ofEpochMilli(now).atZone(utc).toLocalDate().toEpochDay() + 1,
            Instant.ofEpochMilli(trigger).atZone(utc).toLocalDate().toEpochDay(),
        )
    }

    @Test
    fun `exactly on a boundary rolls to the next one (must be strictly ahead)`() {
        val now = millis("2026-06-15T12:00:00Z")
        assertEquals(millis("2026-06-15T17:00:00Z"), WidgetRefreshSchedule.nextBoundaryMillis(now, utc))
    }

    @Test
    fun `just before midnight still triggers at midnight`() {
        val now = millis("2026-06-15T23:59:59Z")
        assertEquals(millis("2026-06-16T00:00:00Z"), WidgetRefreshSchedule.nextBoundaryMillis(now, utc))
    }
}
