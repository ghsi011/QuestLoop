package com.questloop.core.reward

import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionSoundCuesTest {

    private fun cue(
        result: CompletionResult = CompletionResult.COMPLETED,
        fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.5,
        difficulty: Difficulty = Difficulty.MEDIUM,
        xpAwarded: Long = difficulty.baseXp.toLong(),
        leveledUp: Boolean = false,
        unlockedAchievement: Boolean = false,
    ) = CompletionSoundCues.cueFor(result, fraction, difficulty, xpAwarded, leveledUp, unlockedAchievement)

    @Test
    fun `misses are silent - never punish`() {
        assertNull(cue(result = CompletionResult.SKIPPED))
        assertNull(cue(result = CompletionResult.FAILED))
        assertNull(cue(result = CompletionResult.RESCHEDULED))
    }

    @Test
    fun `zero-progress partial is silent`() {
        assertNull(cue(result = CompletionResult.PARTIAL, fraction = 0.0))
    }

    @Test
    fun `partial progress gets the small progress bling`() {
        val sound = cue(result = CompletionResult.PARTIAL, fraction = 0.5, xpAwarded = 10)
        assertEquals(CompletionChime.PROGRESS, sound?.chime)
    }

    @Test
    fun `progress bling grows with fraction but stays small`() {
        val early = cue(result = CompletionResult.PARTIAL, fraction = 0.1)!!
        val late = cue(result = CompletionResult.PARTIAL, fraction = 0.9)!!
        assertTrue(early.volume < late.volume)
        assertTrue(late.volume <= 0.6f)
    }

    @Test
    fun `completed chime tier follows difficulty`() {
        assertEquals(CompletionChime.MINOR, cue(difficulty = Difficulty.TRIVIAL)?.chime)
        assertEquals(CompletionChime.MINOR, cue(difficulty = Difficulty.EASY)?.chime)
        assertEquals(CompletionChime.MAJOR, cue(difficulty = Difficulty.MEDIUM)?.chime)
        assertEquals(CompletionChime.MAJOR, cue(difficulty = Difficulty.HARD)?.chime)
        assertEquals(CompletionChime.TRIUMPH, cue(difficulty = Difficulty.EPIC)?.chime)
    }

    @Test
    fun `volume rises with awarded xp and is clamped to 1`() {
        val small = cue(difficulty = Difficulty.EASY, xpAwarded = 10)!!
        val big = cue(difficulty = Difficulty.HARD, xpAwarded = 56)!!
        val huge = cue(difficulty = Difficulty.EPIC, xpAwarded = 500)!!
        assertTrue(small.volume < big.volume)
        assertEquals(1f, huge.volume)
    }

    @Test
    fun `capped zero-xp completion still gets a quiet acknowledgement`() {
        val sound = cue(difficulty = Difficulty.EASY, xpAwarded = 0)!!
        assertEquals(CompletionChime.MINOR, sound.chime)
        assertEquals(0.45f, sound.volume)
    }

    @Test
    fun `negative-xp record never lowers volume below the floor`() {
        val sound = cue(difficulty = Difficulty.EASY, xpAwarded = -20)!!
        assertEquals(0.45f, sound.volume)
    }

    @Test
    fun `level-up fanfare outranks everything`() {
        val sound = cue(difficulty = Difficulty.TRIVIAL, xpAwarded = 5, leveledUp = true, unlockedAchievement = true)!!
        assertEquals(CompletionChime.LEVEL_UP, sound.chime)
        assertEquals(1f, sound.volume)
    }

    @Test
    fun `achievement unlock plays the triumph chime`() {
        val sound = cue(difficulty = Difficulty.TRIVIAL, unlockedAchievement = true)!!
        assertEquals(CompletionChime.TRIUMPH, sound.chime)
        assertEquals(1f, sound.volume)
    }

    @Test
    fun `level-up on a partial log still celebrates`() {
        val sound = cue(result = CompletionResult.PARTIAL, fraction = 0.5, leveledUp = true)!!
        assertEquals(CompletionChime.LEVEL_UP, sound.chime)
    }

    @Test
    fun `a miss stays silent even when flags claim a level-up`() {
        // A skip can't level up in practice; the guard keeps misses silent regardless.
        assertNull(cue(result = CompletionResult.SKIPPED, leveledUp = true, unlockedAchievement = true))
    }
}
