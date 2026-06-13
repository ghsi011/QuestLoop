package com.questloop.core.reward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreakTrackerTest {

    @Test
    fun `empty history has no streak`() {
        assertEquals(0, StreakTracker.currentStreak(emptySet(), today = 100))
    }

    @Test
    fun `consecutive active days count`() {
        val days = setOf(98L, 99L, 100L)
        assertEquals(3, StreakTracker.currentStreak(days, today = 100, graceDays = 0))
    }

    @Test
    fun `single gap within grace keeps streak alive`() {
        // active 96,97,98, missed 99, active 100, grace 1
        val days = setOf(96L, 97L, 98L, 100L)
        val streak = StreakTracker.currentStreak(days, today = 100, graceDays = 1)
        assertEquals(4, streak)
    }

    @Test
    fun `gap larger than grace breaks streak`() {
        val days = setOf(95L, 96L, 100L) // 97,98,99 missed = 3 misses > grace 1
        val streak = StreakTracker.currentStreak(days, today = 100, graceDays = 1)
        assertEquals(1, streak)
    }

    @Test
    fun `today not yet done still counts existing streak within grace`() {
        val days = setOf(98L, 99L) // today=100 not done, grace 1
        val streak = StreakTracker.currentStreak(days, today = 100, graceDays = 1)
        assertEquals(2, streak)
    }

    @Test
    fun `longest streak finds the biggest consecutive run`() {
        val days = setOf(1L, 2L, 3L, 5L, 6L, 10L, 11L, 12L, 13L)
        assertEquals(4, StreakTracker.longestStreak(days))
        assertEquals(0, StreakTracker.longestStreak(emptySet()))
        assertEquals(1, StreakTracker.longestStreak(setOf(7L)))
    }

    @Test
    fun `streak alive detection respects grace`() {
        val days = setOf(98L, 99L)
        assertTrue(StreakTracker.isStreakAlive(days, today = 100, graceDays = 1))
        assertFalse(StreakTracker.isStreakAlive(days, today = 102, graceDays = 1))
    }
}
