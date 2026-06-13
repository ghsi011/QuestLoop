package com.questloop.core.reward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelSystemTest {

    @Test
    fun `level 1 starts at zero xp`() {
        assertEquals(0L, LevelSystem.xpForLevel(1))
        assertEquals(1, LevelSystem.levelForXp(0))
    }

    @Test
    fun `xp thresholds grow quadratically`() {
        // gaps: L1->L2 = 100, L2->L3 = 200, L3->L4 = 300 ...
        assertEquals(0L, LevelSystem.xpForLevel(1))
        assertEquals(100L, LevelSystem.xpForLevel(2))
        assertEquals(300L, LevelSystem.xpForLevel(3))
        assertEquals(600L, LevelSystem.xpForLevel(4))
        assertEquals(1000L, LevelSystem.xpForLevel(5))
    }

    @Test
    fun `levelForXp is the inverse of xpForLevel at boundaries`() {
        for (level in 1..50) {
            val floor = LevelSystem.xpForLevel(level)
            assertEquals(level, LevelSystem.levelForXp(floor), "floor of level $level")
            if (level < 50) {
                val justBelowNext = LevelSystem.xpForLevel(level + 1) - 1
                assertEquals(level, LevelSystem.levelForXp(justBelowNext), "just below level ${level + 1}")
            }
        }
    }

    @Test
    fun `progress reports fraction within a level`() {
        val mid = LevelSystem.xpForLevel(3) + 100 // halfway through level 3 (span 300)
        val p = LevelSystem.progress(mid)
        assertEquals(3, p.level)
        assertEquals(100L, p.xpIntoLevel)
        assertEquals(300L, p.xpForNextLevel)
        assertTrue(p.fractionToNext in 0.32..0.34)
    }

    @Test
    fun `monotonic non-decreasing levels as xp increases`() {
        var last = 1
        var xp = 0L
        while (xp < 100_000) {
            val l = LevelSystem.levelForXp(xp)
            assertTrue(l >= last, "level decreased at xp=$xp")
            last = l
            xp += 137
        }
    }
}
