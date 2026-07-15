package com.questloop.core.reward

import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionSoundCuesTest {

    private fun cue(
        result: CompletionResult = CompletionResult.COMPLETED,
        fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.5,
        style: CompletionStyle = CompletionStyle.BINARY,
        difficulty: Difficulty = Difficulty.MEDIUM,
        xpAwarded: Long = difficulty.baseXp.toLong(),
        leveledUp: Boolean = false,
        unlockedAchievement: Boolean = false,
        previousFraction: Double? = null,
        previouslyCompleted: Boolean = false,
    ) = CompletionSoundCues.cueFor(
        result, fraction, style, difficulty, xpAwarded,
        leveledUp, unlockedAchievement, previousFraction, previouslyCompleted,
    )

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
        val sound = cue(
            result = CompletionResult.PARTIAL, fraction = 0.5,
            style = CompletionStyle.QUANTITATIVE, xpAwarded = 10,
        )
        assertEquals(CompletionChime.PROGRESS, sound?.chime)
    }

    @Test
    fun `a subjective self-rating below max is that quest's completion moment`() {
        // 4/5 on a HARD reflection records PARTIAL but dismisses the quest for the
        // day — it must earn the difficulty-tier chime, not a "keep going" bling.
        val sound = cue(
            result = CompletionResult.PARTIAL, fraction = 0.8,
            style = CompletionStyle.SUBJECTIVE, difficulty = Difficulty.HARD, xpAwarded = 28,
        )
        assertEquals(CompletionChime.MAJOR, sound?.chime)
    }

    @Test
    fun `a zero self-rating stays silent even for subjective quests`() {
        assertNull(cue(result = CompletionResult.PARTIAL, fraction = 0.0, style = CompletionStyle.SUBJECTIVE))
    }

    @Test
    fun `a re-log that adds no progress is silent`() {
        assertNull(
            cue(
                result = CompletionResult.PARTIAL, fraction = 0.5,
                style = CompletionStyle.QUANTITATIVE, previousFraction = 0.5,
            ),
        )
        assertNull(cue(fraction = 1.0, previousFraction = 1.0, previouslyCompleted = true))
    }

    @Test
    fun `over-completion extras get a quiet progress tick, not the full chime again`() {
        val sound = cue(
            result = CompletionResult.COMPLETED, fraction = 1.5,
            style = CompletionStyle.QUANTITATIVE, difficulty = Difficulty.HARD,
            previousFraction = 1.0, previouslyCompleted = true,
        )!!
        assertEquals(CompletionChime.PROGRESS, sound.chime)
        assertTrue(sound.volume <= 0.6f)
    }

    @Test
    fun `first crossing of the target still earns the full chime`() {
        val sound = cue(
            result = CompletionResult.COMPLETED, fraction = 1.0,
            style = CompletionStyle.QUANTITATIVE, difficulty = Difficulty.MEDIUM,
            previousFraction = 0.6, previouslyCompleted = false,
        )
        assertEquals(CompletionChime.MAJOR, sound?.chime)
    }

    @Test
    fun `a level-up celebrates even when the log adds no progress`() {
        // XP re-scoring on a re-log can cross a level boundary; the level-up is
        // a real event and outranks the no-new-progress silence.
        val sound = cue(fraction = 1.0, previousFraction = 1.0, previouslyCompleted = true, leveledUp = true)
        assertEquals(CompletionChime.LEVEL_UP, sound?.chime)
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
