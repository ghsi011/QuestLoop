package com.questloop.core.generation

import com.questloop.core.model.QuestFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestSchedulerTest {

    @Test
    fun `daily is due unless completed today`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.DAILY, today = 100, lastCompletedEpochDay = 99))
        assertTrue(QuestScheduler.isDue(QuestFrequency.DAILY, today = 100, lastCompletedEpochDay = null))
        assertFalse(QuestScheduler.isDue(QuestFrequency.DAILY, today = 100, lastCompletedEpochDay = 100))
    }

    @Test
    fun `weekly is not due within the period and due once past it`() {
        // Completed 3 days ago -> not due yet.
        assertFalse(QuestScheduler.isDue(QuestFrequency.WEEKLY, today = 100, lastCompletedEpochDay = 97))
        // Exactly 7 days later -> due.
        assertTrue(QuestScheduler.isDue(QuestFrequency.WEEKLY, today = 100, lastCompletedEpochDay = 93))
        // Never completed -> due.
        assertTrue(QuestScheduler.isDue(QuestFrequency.WEEKLY, today = 100, lastCompletedEpochDay = null))
    }

    @Test
    fun `monthly honours a ~30 day period`() {
        assertFalse(QuestScheduler.isDue(QuestFrequency.MONTHLY, today = 100, lastCompletedEpochDay = 80))
        assertTrue(QuestScheduler.isDue(QuestFrequency.MONTHLY, today = 100, lastCompletedEpochDay = 70))
    }

    @Test
    fun `period boundary is exact for weekly and monthly`() {
        // Exactly one day short of the period -> still NOT due (guards >= vs >).
        assertFalse(QuestScheduler.isDue(QuestFrequency.WEEKLY, today = 100, lastCompletedEpochDay = 94)) // delta 6
        assertTrue(QuestScheduler.isDue(QuestFrequency.WEEKLY, today = 100, lastCompletedEpochDay = 93)) // delta 7
        assertFalse(QuestScheduler.isDue(QuestFrequency.MONTHLY, today = 100, lastCompletedEpochDay = 71)) // delta 29
        assertTrue(QuestScheduler.isDue(QuestFrequency.MONTHLY, today = 100, lastCompletedEpochDay = 70)) // delta 30
    }

    @Test
    fun `recurring behaves like daily and monthly is due when never completed`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.RECURRING, today = 100, lastCompletedEpochDay = 99))
        assertFalse(QuestScheduler.isDue(QuestFrequency.RECURRING, today = 100, lastCompletedEpochDay = 100))
        assertTrue(QuestScheduler.isDue(QuestFrequency.MONTHLY, today = 100, lastCompletedEpochDay = null))
    }

    @Test
    fun `one-off is due only until first completion`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.ONE_OFF, today = 100, lastCompletedEpochDay = null))
        assertFalse(QuestScheduler.isDue(QuestFrequency.ONE_OFF, today = 100, lastCompletedEpochDay = 50))
    }

    @Test
    fun `seasonal is always eligible`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.SEASONAL, today = 100, lastCompletedEpochDay = 99))
        assertTrue(QuestScheduler.isDue(QuestFrequency.SEASONAL, today = 100, lastCompletedEpochDay = null))
    }

    // --- expectedOccurrences over a window -------------------------------------

    @Test
    fun `daily occurs every day of the window when never completed`() {
        // Inclusive 7-day window -> 7 daily occurrences.
        assertEquals(7, QuestScheduler.expectedOccurrences(QuestFrequency.DAILY, from = 100, to = 106, null))
    }

    @Test
    fun `daily completed today is not counted again today`() {
        // Completed on the window's first day -> only the remaining 6 days count.
        assertEquals(6, QuestScheduler.expectedOccurrences(QuestFrequency.DAILY, from = 100, to = 106, 100))
    }

    @Test
    fun `weekly is expected once across a 7-day window`() {
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.WEEKLY, from = 100, to = 106, null))
        // ~30-day window holds 5 weekly slots (days 0,7,14,21,28).
        assertEquals(5, QuestScheduler.expectedOccurrences(QuestFrequency.WEEKLY, from = 100, to = 129, null))
    }

    @Test
    fun `weekly recently completed does not recur until the period passes`() {
        // Completed 3 days before the window -> next due is day 100+ (97+7=104), 1 slot.
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.WEEKLY, from = 100, to = 106, 97))
        // Completed on day 105 inside a 7-day window -> next due 112, beyond it: 0.
        assertEquals(0, QuestScheduler.expectedOccurrences(QuestFrequency.WEEKLY, from = 100, to = 106, 105))
    }

    @Test
    fun `monthly is expected at most once across a month window`() {
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.MONTHLY, from = 100, to = 129, null))
        assertEquals(0, QuestScheduler.expectedOccurrences(QuestFrequency.MONTHLY, from = 100, to = 129, 110))
    }

    @Test
    fun `monthly counts once across a full 31-day calendar month`() {
        // The 1st..31st inclusive (span 31) would naively fence-post to 2 at the
        // 30-day period boundary — capped to a single calendar month.
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.MONTHLY, from = 0, to = 30, null))
        // A two-month window still allows two.
        assertEquals(2, QuestScheduler.expectedOccurrences(QuestFrequency.MONTHLY, from = 0, to = 61, null))
    }

    @Test
    fun `one-off counts once until done then zero, seasonal always once`() {
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.ONE_OFF, from = 100, to = 130, null))
        assertEquals(0, QuestScheduler.expectedOccurrences(QuestFrequency.ONE_OFF, from = 100, to = 130, 90))
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.SEASONAL, from = 100, to = 130, 95))
    }

    @Test
    fun `inverted window yields no occurrences`() {
        assertEquals(0, QuestScheduler.expectedOccurrences(QuestFrequency.DAILY, from = 106, to = 100, null))
    }

    @Test
    fun `a future-dated completion does not hide a recurring quest`() {
        // Clock skew / timezone can stamp a completion after the window end. It
        // must not push the next due date out and silently drop the quest.
        assertEquals(7, QuestScheduler.expectedOccurrences(QuestFrequency.DAILY, from = 100, to = 106, 200))
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.WEEKLY, from = 100, to = 106, 200))
        assertEquals(1, QuestScheduler.expectedOccurrences(QuestFrequency.MONTHLY, from = 100, to = 129, 999))
        assertEquals(100L, QuestScheduler.firstDueDay(QuestFrequency.DAILY, from = 100, to = 106, 200))
        // A one-off, however, stays done regardless of when it was logged.
        assertEquals(0, QuestScheduler.expectedOccurrences(QuestFrequency.ONE_OFF, from = 100, to = 106, 200))
    }

    // --- firstDueDay -----------------------------------------------------------

    @Test
    fun `firstDueDay is the window start for a fresh daily quest`() {
        assertEquals(100L, QuestScheduler.firstDueDay(QuestFrequency.DAILY, from = 100, to = 106, null))
    }

    @Test
    fun `firstDueDay respects the period since last completion`() {
        // Weekly completed on day 97 -> first due back on 104.
        assertEquals(104L, QuestScheduler.firstDueDay(QuestFrequency.WEEKLY, from = 100, to = 110, 97))
        // If that lands beyond the window, there's no due day.
        assertNull(QuestScheduler.firstDueDay(QuestFrequency.WEEKLY, from = 100, to = 103, 97))
    }

    @Test
    fun `firstDueDay is null for an already-done one-off`() {
        assertNull(QuestScheduler.firstDueDay(QuestFrequency.ONE_OFF, from = 100, to = 130, 90))
        assertEquals(100L, QuestScheduler.firstDueDay(QuestFrequency.ONE_OFF, from = 100, to = 130, null))
    }
}
