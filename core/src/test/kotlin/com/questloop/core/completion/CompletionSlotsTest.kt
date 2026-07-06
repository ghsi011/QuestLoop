package com.questloop.core.completion

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionSlotsTest {

    // A Monday, so week math is unambiguous (ISO weeks start Monday).
    private val monday = LocalDate.of(2026, 6, 22).toEpochDay()

    private fun quest(
        frequency: QuestFrequency,
        style: CompletionStyle,
    ) = Quest(
        id = "q",
        title = "Quest",
        category = QuestCategory.HEALTH,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        completionStyle = style,
        targetCount = if (style == CompletionStyle.QUANTITATIVE) 2 else null,
    )

    @Test
    fun `only counting and timed quests accumulate`() {
        assertTrue(CompletionSlots.accumulates(quest(QuestFrequency.WEEKLY, CompletionStyle.QUANTITATIVE)))
        assertTrue(CompletionSlots.accumulates(quest(QuestFrequency.WEEKLY, CompletionStyle.DURATION)))
        assertFalse(CompletionSlots.accumulates(quest(QuestFrequency.WEEKLY, CompletionStyle.BINARY)))
        assertFalse(CompletionSlots.accumulates(quest(QuestFrequency.WEEKLY, CompletionStyle.SUBJECTIVE)))
    }

    @Test
    fun `only measured weekly or monthly quests have a calendar interval`() {
        assertTrue(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.WEEKLY, CompletionStyle.QUANTITATIVE)))
        assertTrue(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.MONTHLY, CompletionStyle.DURATION)))
        // Measured, but per-day cadence: the "interval" is just the day.
        assertFalse(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.DAILY, CompletionStyle.QUANTITATIVE)))
        assertFalse(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.ONE_OFF, CompletionStyle.DURATION)))
        // Weekly/monthly, but not measured: one completion satisfies the cadence.
        assertFalse(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.WEEKLY, CompletionStyle.BINARY)))
        assertFalse(CompletionSlots.hasCalendarInterval(quest(QuestFrequency.MONTHLY, CompletionStyle.SUBJECTIVE)))
    }

    @Test
    fun `a weekly measured quest is slotted to the Monday of its week (Monday start)`() {
        val swim = quest(QuestFrequency.WEEKLY, CompletionStyle.QUANTITATIVE)
        val mon = DayOfWeek.MONDAY
        assertEquals(monday.toString(), CompletionSlots.completionSlot(swim, monday, mon))
        assertEquals(monday.toString(), CompletionSlots.completionSlot(swim, monday + 2, mon)) // Wednesday
        assertEquals(monday.toString(), CompletionSlots.completionSlot(swim, monday + 6, mon)) // Sunday
        // The next calendar week is a fresh slot.
        assertEquals((monday + 7).toString(), CompletionSlots.completionSlot(swim, monday + 7, mon))
    }

    @Test
    fun `a weekly measured quest is slotted to the start of its week (default Sunday)`() {
        val swim = quest(QuestFrequency.WEEKLY, CompletionStyle.QUANTITATIVE)
        // Default first-day is Sunday, so the week containing Monday 2026-06-22
        // starts on Sunday 2026-06-21.
        val sunday = monday - 1
        assertEquals(sunday.toString(), CompletionSlots.completionSlot(swim, monday))
        assertEquals(sunday.toString(), CompletionSlots.completionSlot(swim, sunday)) // the Sunday itself
        assertEquals(sunday.toString(), CompletionSlots.completionSlot(swim, monday + 5)) // Saturday 6/27
        // The following Sunday opens a fresh slot.
        assertEquals((sunday + 7).toString(), CompletionSlots.completionSlot(swim, sunday + 7))
    }

    @Test
    fun `a monthly measured quest is slotted to the first of its month`() {
        val budget = quest(QuestFrequency.MONTHLY, CompletionStyle.DURATION)
        val jun1 = LocalDate.of(2026, 6, 1).toEpochDay()
        assertEquals(jun1.toString(), CompletionSlots.completionSlot(budget, jun1))
        assertEquals(jun1.toString(), CompletionSlots.completionSlot(budget, LocalDate.of(2026, 6, 30).toEpochDay()))
        assertEquals(
            LocalDate.of(2026, 7, 1).toEpochDay().toString(),
            CompletionSlots.completionSlot(budget, LocalDate.of(2026, 7, 1).toEpochDay()),
        )
    }

    @Test
    fun `everything else is slotted to the day itself`() {
        val wednesday = monday + 2
        fun slot(frequency: QuestFrequency, style: CompletionStyle) =
            CompletionSlots.completionSlot(quest(frequency, style), wednesday)
        // Measured daily: per-day semantics.
        assertEquals(wednesday.toString(), slot(QuestFrequency.DAILY, CompletionStyle.QUANTITATIVE))
        // Weekly/monthly but binary/subjective: keyed to the day it was done.
        assertEquals(wednesday.toString(), slot(QuestFrequency.WEEKLY, CompletionStyle.BINARY))
        assertEquals(wednesday.toString(), slot(QuestFrequency.MONTHLY, CompletionStyle.SUBJECTIVE))
    }

    @Test
    fun `a measured one-off accumulates in one fixed lifetime slot`() {
        val wednesday = monday + 2
        // A ONE_OFF measured quest keys every day to the same lifetime slot, so
        // progress accumulates across days into one record instead of resetting daily.
        val readPages = quest(QuestFrequency.ONE_OFF, CompletionStyle.DURATION)
        assertEquals("oneoff", CompletionSlots.completionSlot(readPages, wednesday))
        assertEquals("oneoff", CompletionSlots.completionSlot(readPages, wednesday + 5))
    }

    @Test
    fun `intervalStartFor falls back to the day for non-calendar frequencies`() {
        val wednesday = monday + 2
        val mon = DayOfWeek.MONDAY
        assertEquals(monday, CompletionSlots.intervalStartFor(QuestFrequency.WEEKLY, wednesday, mon))
        assertEquals(
            LocalDate.of(2026, 6, 1).toEpochDay(),
            CompletionSlots.intervalStartFor(QuestFrequency.MONTHLY, wednesday),
        )
        assertEquals(wednesday, CompletionSlots.intervalStartFor(QuestFrequency.DAILY, wednesday))
        assertEquals(wednesday, CompletionSlots.intervalStartFor(QuestFrequency.ONE_OFF, wednesday))
    }

    @Test
    fun `nextIntervalStart advances to the following week, month, or day`() {
        // Any day of the week advances to the next Monday (Monday-start weeks here).
        val mon = DayOfWeek.MONDAY
        assertEquals(monday + 7, CompletionSlots.nextIntervalStart(QuestFrequency.WEEKLY, monday, mon))
        assertEquals(monday + 7, CompletionSlots.nextIntervalStart(QuestFrequency.WEEKLY, monday + 6, mon))
        // Any day of the month advances to the 1st of the next month (across years too).
        assertEquals(
            LocalDate.of(2026, 7, 1).toEpochDay(),
            CompletionSlots.nextIntervalStart(QuestFrequency.MONTHLY, LocalDate.of(2026, 6, 15).toEpochDay()),
        )
        assertEquals(
            LocalDate.of(2027, 1, 1).toEpochDay(),
            CompletionSlots.nextIntervalStart(QuestFrequency.MONTHLY, LocalDate.of(2026, 12, 20).toEpochDay()),
        )
        assertEquals(monday + 1, CompletionSlots.nextIntervalStart(QuestFrequency.DAILY, monday))
    }

    @Test
    fun `week boundaries bracket the ISO week (Monday start)`() {
        val mon = DayOfWeek.MONDAY
        val sunday = monday + 6
        assertEquals(monday, CompletionSlots.startOfWeek(monday, mon))
        assertEquals(monday, CompletionSlots.startOfWeek(sunday, mon))
        assertEquals(sunday, CompletionSlots.endOfWeek(monday, mon))
        assertEquals(sunday, CompletionSlots.endOfWeek(sunday, mon))
        // The day after Sunday belongs to the next week.
        assertEquals(monday + 7, CompletionSlots.startOfWeek(sunday + 1, mon))
    }

    @Test
    fun `week boundaries bracket the Sunday-Saturday week (default)`() {
        // Default first-day is Sunday: the week runs Sun 6/21 .. Sat 6/27.
        val sunday = monday - 1
        val saturday = monday + 5
        assertEquals(sunday, CompletionSlots.startOfWeek(sunday))
        assertEquals(sunday, CompletionSlots.startOfWeek(monday)) // Monday belongs to that week
        assertEquals(sunday, CompletionSlots.startOfWeek(saturday))
        assertEquals(saturday, CompletionSlots.endOfWeek(sunday))
        assertEquals(saturday, CompletionSlots.endOfWeek(saturday))
        // The next Sunday opens the following week.
        assertEquals(sunday + 7, CompletionSlots.startOfWeek(saturday + 1))
    }

    @Test
    fun `month boundaries bracket the calendar month, leap year included`() {
        val jun10 = LocalDate.of(2026, 6, 10).toEpochDay()
        assertEquals(LocalDate.of(2026, 6, 1).toEpochDay(), CompletionSlots.startOfMonth(jun10))
        assertEquals(LocalDate.of(2026, 6, 30).toEpochDay(), CompletionSlots.endOfMonth(jun10))
        // 2028 is a leap year: February ends on the 29th.
        val leapFeb = LocalDate.of(2028, 2, 10).toEpochDay()
        assertEquals(LocalDate.of(2028, 2, 29).toEpochDay(), CompletionSlots.endOfMonth(leapFeb))
    }
}
