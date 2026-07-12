package com.questloop.core.generation

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestScheduleTest {

    private fun quest(
        frequency: QuestFrequency = QuestFrequency.DAILY,
        style: CompletionStyle = CompletionStyle.BINARY,
        times: List<Int> = emptyList(),
        dayOfWeek: DayOfWeek? = null,
        dayOfMonth: Int? = null,
        total: Int? = null,
        reminders: Boolean = false,
        targetCount: Int? = null,
    ) = Quest(
        id = "q",
        title = "Test",
        category = QuestCategory.HEALTH,
        frequency = frequency,
        difficulty = Difficulty.EASY,
        completionStyle = style,
        targetCount = targetCount,
        scheduledTimes = times,
        scheduledDayOfWeek = dayOfWeek,
        scheduledDayOfMonth = dayOfMonth,
        totalOccurrences = total,
        remindersEnabled = reminders,
    )

    // Monday 2024-01-01 (epoch day 19723) anchors most calendar tests.
    private val mon = LocalDate.of(2024, 1, 1).toEpochDay()

    // --- retirement -----------------------------------------------------------

    @Test
    fun `retires exactly at the occurrence limit`() {
        val q = quest(total = 5)
        assertFalse(QuestSchedule.isRetired(q, 4))
        assertTrue(QuestSchedule.isRetired(q, 5))
        assertTrue(QuestSchedule.isRetired(q, 6))
        assertFalse(QuestSchedule.isRetired(quest(total = null), 100))
    }

    @Test
    fun `occurrences count distinct intervals, not completion records`() {
        // Two rent payments logged inside the same calendar month = ONE occurrence;
        // a raw record count would burn two months of a 12-month run.
        val rent = quest(frequency = QuestFrequency.MONTHLY, total = 12)
        val jan5 = LocalDate.of(2024, 1, 5).toEpochDay()
        val jan20 = LocalDate.of(2024, 1, 20).toEpochDay()
        val feb1 = LocalDate.of(2024, 2, 1).toEpochDay()
        assertEquals(1, QuestSchedule.completedOccurrences(rent, listOf(jan5, jan20), DayOfWeek.SUNDAY))
        assertEquals(2, QuestSchedule.completedOccurrences(rent, listOf(jan5, jan20, feb1), DayOfWeek.SUNDAY))

        // Weekly: same week (Mon-start) = one; across the boundary = two.
        val weekly = quest(frequency = QuestFrequency.WEEKLY)
        assertEquals(1, QuestSchedule.completedOccurrences(weekly, listOf(mon, mon + 3), DayOfWeek.MONDAY))
        assertEquals(2, QuestSchedule.completedOccurrences(weekly, listOf(mon, mon + 7), DayOfWeek.MONDAY))
        // ...and the week-start preference decides the boundary: Sunday belongs to
        // the Monday-start week's tail but starts a fresh Sunday-start week.
        val sat = mon + 5
        val sun = mon + 6
        assertEquals(1, QuestSchedule.completedOccurrences(weekly, listOf(sat, sun), DayOfWeek.MONDAY))
        assertEquals(2, QuestSchedule.completedOccurrences(weekly, listOf(sat, sun), DayOfWeek.SUNDAY))

        // Daily: every day is its own interval.
        assertEquals(3, QuestSchedule.completedOccurrences(quest(), listOf(mon, mon + 1, mon + 2), DayOfWeek.MONDAY))
    }

    @Test
    fun `retired quest is never due`() {
        val q = quest(frequency = QuestFrequency.DAILY, total = 5)
        assertTrue(
            QuestSchedule.isDue(q, today = 100, lastCompletedEpochDay = 99, completedOccurrences = 4, firstDayOfWeek = DayOfWeek.SUNDAY),
        )
        assertFalse(
            QuestSchedule.isDue(q, today = 100, lastCompletedEpochDay = 99, completedOccurrences = 5, firstDayOfWeek = DayOfWeek.SUNDAY),
        )
    }

    // --- anchored dueness -----------------------------------------------------

    @Test
    fun `weekly anchor - not due before the anchor day, due from it until completed`() {
        val q = quest(frequency = QuestFrequency.WEEKLY, dayOfWeek = DayOfWeek.WEDNESDAY)
        // Week starts Monday; anchor Wednesday = mon + 2.
        assertFalse(QuestSchedule.isDue(q, mon, null, 0, DayOfWeek.MONDAY)) // Monday
        assertFalse(QuestSchedule.isDue(q, mon + 1, null, 0, DayOfWeek.MONDAY)) // Tuesday
        assertTrue(QuestSchedule.isDue(q, mon + 2, null, 0, DayOfWeek.MONDAY)) // Wednesday
        // Missed the anchor: still due for the rest of the week.
        assertTrue(QuestSchedule.isDue(q, mon + 5, null, 0, DayOfWeek.MONDAY)) // Saturday
        // Completed on the anchor: quiet for the rest of the week...
        assertFalse(QuestSchedule.isDue(q, mon + 5, mon + 2, 1, DayOfWeek.MONDAY))
        // ...and due again on the next week's anchor day.
        assertTrue(QuestSchedule.isDue(q, mon + 9, mon + 2, 1, DayOfWeek.MONDAY))
    }

    @Test
    fun `weekly anchor honours the user's first day of week`() {
        // 2024-01-07 is a Sunday. With a Sunday week start, the week containing
        // it runs Sun..Sat, so a Sunday-anchored quest is due that day.
        val sun = LocalDate.of(2024, 1, 7).toEpochDay()
        val q = quest(frequency = QuestFrequency.WEEKLY, dayOfWeek = DayOfWeek.SUNDAY)
        assertTrue(QuestSchedule.isDue(q, sun, null, 0, DayOfWeek.SUNDAY))
        // Completing it Sunday silences the whole Sun..Sat week.
        assertFalse(QuestSchedule.isDue(q, sun + 6, sun, 1, DayOfWeek.SUNDAY))
        // With a Monday week start the same Sunday belongs to the *previous* week,
        // so a completion there leaves the next (Mon-start) week's Sunday due.
        assertTrue(QuestSchedule.isDue(q, sun + 7, sun, 1, DayOfWeek.MONDAY))
    }

    @Test
    fun `weekly anchor - completing early in the interval satisfies the anchor`() {
        val q = quest(frequency = QuestFrequency.WEEKLY, dayOfWeek = DayOfWeek.FRIDAY)
        // Completed Tuesday; anchor Friday of the same week stays quiet.
        assertFalse(QuestSchedule.isDue(q, mon + 4, mon + 1, 1, DayOfWeek.MONDAY))
    }

    @Test
    fun `monthly anchor - due from the anchor date until completed, resets next month`() {
        val q = quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 5)
        val jan1 = LocalDate.of(2024, 1, 1).toEpochDay()
        val jan5 = LocalDate.of(2024, 1, 5).toEpochDay()
        val jan20 = LocalDate.of(2024, 1, 20).toEpochDay()
        val feb5 = LocalDate.of(2024, 2, 5).toEpochDay()
        assertFalse(QuestSchedule.isDue(q, jan1, null, 0, DayOfWeek.SUNDAY))
        assertTrue(QuestSchedule.isDue(q, jan5, null, 0, DayOfWeek.SUNDAY))
        assertTrue(QuestSchedule.isDue(q, jan20, null, 0, DayOfWeek.SUNDAY)) // missed, still owed
        assertFalse(QuestSchedule.isDue(q, jan20, jan5, 1, DayOfWeek.SUNDAY)) // paid
        assertTrue(QuestSchedule.isDue(q, feb5, jan5, 1, DayOfWeek.SUNDAY)) // next month
    }

    @Test
    fun `monthly anchor clamps to short months`() {
        val q = quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 31)
        val feb29 = LocalDate.of(2024, 2, 29).toEpochDay() // 2024 is a leap year
        assertEquals(feb29, QuestSchedule.anchorDayIn(q, LocalDate.of(2024, 2, 10).toEpochDay(), DayOfWeek.SUNDAY))
        assertTrue(QuestSchedule.isDue(q, feb29, null, 0, DayOfWeek.SUNDAY))
    }

    @Test
    fun `unanchored quests keep the rolling cadence`() {
        val weekly = quest(frequency = QuestFrequency.WEEKLY)
        assertFalse(QuestSchedule.isDue(weekly, 100, 97, 1, DayOfWeek.SUNDAY))
        assertTrue(QuestSchedule.isDue(weekly, 100, 93, 1, DayOfWeek.SUNDAY))
        val daily = quest(frequency = QuestFrequency.DAILY, times = listOf(8 * 60, 20 * 60))
        assertTrue(QuestSchedule.isDue(daily, 100, 99, 1, DayOfWeek.SUNDAY))
        assertFalse(QuestSchedule.isDue(daily, 100, 100, 1, DayOfWeek.SUNDAY))
    }

    @Test
    fun `anchor on a measured interval quest never gates dueness`() {
        // "Swim 2x/week" measured quests accumulate over the calendar interval;
        // their visibility is interval dismissal, so isDue must stay the rolling
        // window even when an anchor day is set.
        val q = quest(
            frequency = QuestFrequency.WEEKLY,
            style = CompletionStyle.QUANTITATIVE,
            targetCount = 2,
            dayOfWeek = DayOfWeek.FRIDAY,
        )
        assertFalse(QuestSchedule.hasDueAnchor(q))
        assertTrue(QuestSchedule.isDue(q, mon, null, 0, DayOfWeek.MONDAY)) // rolling: never completed
    }

    // --- normalization --------------------------------------------------------

    @Test
    fun `normalized turns a multi-time binary quest into a per-slot count`() {
        val q = QuestSchedule.normalized(quest(times = listOf(20 * 60, 8 * 60), reminders = true))
        assertEquals(CompletionStyle.QUANTITATIVE, q.completionStyle)
        assertEquals(2, q.targetCount)
        assertEquals("times", q.unit)
        assertEquals(listOf(8 * 60, 20 * 60), q.scheduledTimes) // sorted
        assertTrue(q.remindersEnabled)
    }

    @Test
    fun `normalized keeps an explicitly measured quest's own target`() {
        val q = QuestSchedule.normalized(
            quest(style = CompletionStyle.QUANTITATIVE, targetCount = 8, times = listOf(9 * 60, 15 * 60)),
        )
        assertEquals(8, q.targetCount)
        assertEquals(CompletionStyle.QUANTITATIVE, q.completionStyle)
    }

    @Test
    fun `normalized single-time binary quest stays binary`() {
        val q = QuestSchedule.normalized(quest(times = listOf(8 * 60)))
        assertEquals(CompletionStyle.BINARY, q.completionStyle)
        assertNull(q.targetCount)
    }

    @Test
    fun `normalized scrubs invalid and mismatched schedule fields`() {
        val q = QuestSchedule.normalized(
            quest(
                frequency = QuestFrequency.DAILY,
                times = listOf(-5, 8 * 60, 8 * 60, 5000),
                dayOfWeek = DayOfWeek.MONDAY, // not WEEKLY -> dropped
                dayOfMonth = 40, // not MONTHLY -> dropped
                total = 0, // non-positive -> no limit
                reminders = true,
            ),
        )
        assertEquals(listOf(8 * 60), q.scheduledTimes)
        assertNull(q.scheduledDayOfWeek)
        assertNull(q.scheduledDayOfMonth)
        assertNull(q.totalOccurrences)
        assertTrue(q.remindersEnabled) // one valid time survives
    }

    @Test
    fun `normalized clears schedule on non-recurring quests and caps times`() {
        val oneOff = QuestSchedule.normalized(
            quest(frequency = QuestFrequency.ONE_OFF, times = listOf(8 * 60), total = 3, reminders = true),
        )
        assertTrue(oneOff.scheduledTimes.isEmpty())
        assertNull(oneOff.totalOccurrences)
        assertFalse(oneOff.remindersEnabled)

        val crowded = QuestSchedule.normalized(quest(times = (0 until 10).map { it * 60 }))
        assertEquals(QuestSchedule.MAX_TIMES_PER_DAY, crowded.scheduledTimes.size)
    }

    @Test
    fun `normalized keeps a single time on weekly and monthly quests`() {
        // Several times/day on a weekly quest would convert it to a measured count,
        // which would stop the anchor from gating dueness — so the earliest time
        // wins, the quest stays BINARY, and the anchor keeps working.
        val q = QuestSchedule.normalized(
            quest(
                frequency = QuestFrequency.WEEKLY,
                dayOfWeek = DayOfWeek.WEDNESDAY,
                times = listOf(20 * 60, 8 * 60),
            ),
        )
        assertEquals(listOf(8 * 60), q.scheduledTimes)
        assertEquals(CompletionStyle.BINARY, q.completionStyle)
        assertTrue(QuestSchedule.hasDueAnchor(q))
    }

    @Test
    fun `normalized clamps a monthly day-of-month into range`() {
        val q = QuestSchedule.normalized(quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 45))
        assertEquals(31, q.scheduledDayOfMonth)
    }

    // --- next scheduled day / reminder instant ---------------------------------

    @Test
    fun `nextScheduledDay is the day itself for daily, the anchor or its due tail otherwise`() {
        assertEquals(mon, QuestSchedule.nextScheduledDay(quest(), mon, DayOfWeek.MONDAY))
        val wed = quest(frequency = QuestFrequency.WEEKLY, dayOfWeek = DayOfWeek.WEDNESDAY)
        assertEquals(mon + 2, QuestSchedule.nextScheduledDay(wed, mon, DayOfWeek.MONDAY))
        // From Thursday the anchor is past but the quest can still be due (missed
        // Wednesday) — the reminder keeps landing through the interval's tail; the
        // fire-time gate silences it once completed.
        assertEquals(mon + 3, QuestSchedule.nextScheduledDay(wed, mon + 3, DayOfWeek.MONDAY))

        val rent = quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 1)
        val jan15 = LocalDate.of(2024, 1, 15).toEpochDay()
        assertEquals(jan15, QuestSchedule.nextScheduledDay(rent, jan15, DayOfWeek.SUNDAY)) // due tail
        val feb1 = LocalDate.of(2024, 2, 1).toEpochDay()
        assertEquals(feb1, QuestSchedule.nextScheduledDay(rent, feb1, DayOfWeek.SUNDAY))
    }

    @Test
    fun `monthly nextScheduledDay clamps and waits for the anchor before the tail`() {
        val q = quest(frequency = QuestFrequency.MONTHLY, dayOfMonth = 31)
        val feb10 = LocalDate.of(2024, 2, 10).toEpochDay()
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), QuestSchedule.nextScheduledDay(q, feb10, DayOfWeek.SUNDAY))
        val mar1 = LocalDate.of(2024, 3, 1).toEpochDay()
        assertEquals(LocalDate.of(2024, 3, 31).toEpochDay(), QuestSchedule.nextScheduledDay(q, mar1, DayOfWeek.SUNDAY))
    }

    @Test
    fun `nextTriggerMillis picks the next time today, then rolls over`() {
        val zone = ZoneId.of("UTC")
        val q = quest(times = listOf(8 * 60, 20 * 60))
        val at7 = ZonedDateTime.of(2024, 1, 1, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val at9 = ZonedDateTime.of(2024, 1, 1, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val at21 = ZonedDateTime.of(2024, 1, 1, 21, 0, 0, 0, zone).toInstant().toEpochMilli()
        val eight = ZonedDateTime.of(2024, 1, 1, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val twenty = ZonedDateTime.of(2024, 1, 1, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val eightTomorrow = ZonedDateTime.of(2024, 1, 2, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(eight, QuestSchedule.nextTriggerMillis(q, at7, DayOfWeek.SUNDAY, zone))
        assertEquals(twenty, QuestSchedule.nextTriggerMillis(q, at9, DayOfWeek.SUNDAY, zone))
        assertEquals(eightTomorrow, QuestSchedule.nextTriggerMillis(q, at21, DayOfWeek.SUNDAY, zone))
        // Exactly at the instant -> strictly after, so the next slot.
        assertEquals(twenty, QuestSchedule.nextTriggerMillis(q, eight, DayOfWeek.SUNDAY, zone))
    }

    @Test
    fun `nextTriggerMillis lands on the weekly anchor day`() {
        val zone = ZoneId.of("UTC")
        val q = quest(frequency = QuestFrequency.WEEKLY, dayOfWeek = DayOfWeek.WEDNESDAY, times = listOf(9 * 60))
        // Monday 2024-01-01 10:00 -> Wednesday 2024-01-03 09:00.
        val now = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2024, 1, 3, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, QuestSchedule.nextTriggerMillis(q, now, DayOfWeek.MONDAY, zone))
    }

    @Test
    fun `nextTriggerMillis is null without times`() {
        assertNull(QuestSchedule.nextTriggerMillis(quest(), 0L, DayOfWeek.SUNDAY, ZoneId.of("UTC")))
    }
}
