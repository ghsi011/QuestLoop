package com.questloop.core.calendar

import kotlin.test.Test
import kotlin.test.assertEquals

class FreeBusyCalculatorTest {

    // Default waking window is 08:00–22:00 = 840 minutes.
    private val fullDay = DayWindow.DEFAULT_DAY_END_MINUTE - DayWindow.DEFAULT_DAY_START_MINUTE

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute
    private fun busy(startHour: Int, endHour: Int) = Interval(at(startHour), at(endHour))

    @Test
    fun `an empty calendar leaves the whole window free`() {
        assertEquals(fullDay, FreeBusyCalculator.freeMinutes(emptyList()))
    }

    @Test
    fun `a single meeting subtracts its length`() {
        // 09:00–10:30 = 90 minutes busy.
        val free = FreeBusyCalculator.freeMinutes(listOf(Interval(at(9), at(10, 30))))
        assertEquals(fullDay - 90, free)
    }

    @Test
    fun `overlapping meetings are not double-counted`() {
        // 09:00–11:00 and 10:00–12:00 overlap -> a single 09:00–12:00 (180 min) block.
        val free = FreeBusyCalculator.freeMinutes(listOf(busy(9, 11), busy(10, 12)))
        assertEquals(fullDay - 180, free)
    }

    @Test
    fun `back-to-back meetings merge into one block`() {
        // 09:00–10:00 and 10:00–11:00 are adjacent -> 120 min busy, not a gap between.
        val merged = FreeBusyCalculator.mergedBusy(listOf(busy(9, 10), busy(10, 11)))
        assertEquals(listOf(Interval(at(9), at(11))), merged)
    }

    @Test
    fun `events outside the waking window are ignored`() {
        // 06:00–07:00 (before 08:00) and 23:00–23:30 (after 22:00) don't count.
        val free = FreeBusyCalculator.freeMinutes(listOf(busy(6, 7), Interval(at(23), at(23, 30))))
        assertEquals(fullDay, free)
    }

    @Test
    fun `an event straddling the window edge is clipped to the window`() {
        // 07:00–09:00 -> only 08:00–09:00 (60 min) falls inside the window.
        val free = FreeBusyCalculator.freeMinutes(listOf(busy(7, 9)))
        assertEquals(fullDay - 60, free)
    }

    @Test
    fun `a fully booked window leaves no free time`() {
        val free = FreeBusyCalculator.freeMinutes(listOf(busy(8, 22)))
        assertEquals(0, free)
    }

    @Test
    fun `free intervals are the gaps between meetings`() {
        // Busy 10:00–11:00 and 14:00–15:00 -> free 08:00–10:00, 11:00–14:00, 15:00–22:00.
        val gaps = FreeBusyCalculator.freeIntervals(listOf(busy(10, 11), busy(14, 15)))
        assertEquals(
            listOf(Interval(at(8), at(10)), Interval(at(11), at(14)), Interval(at(15), at(22))),
            gaps,
        )
    }

    @Test
    fun `a custom window restricts free time to remaining hours`() {
        // From 18:00 with a 19:00–20:00 dinner -> 18–19 and 20–22 free = 180 min.
        val window = DayWindow(startMinute = at(18), endMinute = at(22))
        val free = FreeBusyCalculator.freeMinutes(listOf(busy(19, 20)), window)
        assertEquals(180, free)
    }

    @Test
    fun `an inverted or empty window yields no free time`() {
        assertEquals(0, FreeBusyCalculator.freeMinutes(emptyList(), DayWindow(at(22), at(8))))
        assertEquals(0, FreeBusyCalculator.freeMinutes(emptyList(), DayWindow(at(12), at(12))))
    }
}
