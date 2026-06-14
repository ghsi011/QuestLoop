package com.questloop.core.generation

import com.questloop.core.model.QuestFrequency
import kotlin.test.Test
import kotlin.test.assertFalse
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
    fun `one-off is due only until first completion`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.ONE_OFF, today = 100, lastCompletedEpochDay = null))
        assertFalse(QuestScheduler.isDue(QuestFrequency.ONE_OFF, today = 100, lastCompletedEpochDay = 50))
    }

    @Test
    fun `seasonal is always eligible`() {
        assertTrue(QuestScheduler.isDue(QuestFrequency.SEASONAL, today = 100, lastCompletedEpochDay = 99))
        assertTrue(QuestScheduler.isDue(QuestFrequency.SEASONAL, today = 100, lastCompletedEpochDay = null))
    }
}
