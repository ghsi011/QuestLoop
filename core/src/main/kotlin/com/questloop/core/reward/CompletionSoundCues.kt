package com.questloop.core.reward

import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty

/**
 * Which celebration chime a completion earns. Ordered roughly by "size" of the
 * moment; the app maps each to a bundled sound asset.
 */
enum class CompletionChime {
    /** Soft, tiny bling: a partial progress log on a measured quest. */
    PROGRESS,

    /** Small bling: a trivial/easy quest completed. */
    MINOR,

    /** Fuller chime: a medium/hard quest completed. */
    MAJOR,

    /** Big ascending chime: an epic quest completed, or an achievement unlocked. */
    TRIUMPH,

    /** Distinct fanfare: the completion crossed a level boundary. */
    LEVEL_UP,
}

/** A chime plus the relative loudness (0..1) it should play at. */
data class CompletionSound(val chime: CompletionChime, val volume: Float)

/**
 * Pure mapping from a completion's outcome to the celebration sound it earns —
 * the classic-conditioning cue: bigger wins sound bigger, small progress gets a
 * small bling, and misses are silent (SPEC 8: never punish).
 */
object CompletionSoundCues {

    /** XP that maps to full volume for a completed quest (≈ epic + bonuses). */
    private const val FULL_VOLUME_XP = 90f

    /** Loudness floor so even a fully-capped (0 XP) completion is audible. */
    private const val MIN_COMPLETE_VOLUME = 0.45f

    /**
     * Decides the sound for one recorded completion, or null for silence.
     *
     * @param result the recorded outcome (only COMPLETED/PARTIAL ever sound).
     * @param fraction 0..1 progress carried by the record (PARTIAL logs).
     * @param difficulty the quest's difficulty tier (picks the chime).
     * @param xpAwarded XP the ledger actually granted — post-multiplier,
     *   post-cap — so the loudness tracks real earned value.
     * @param leveledUp whether this completion crossed a level boundary.
     * @param unlockedAchievement whether it unlocked a new achievement.
     */
    fun cueFor(
        result: CompletionResult,
        fraction: Double,
        difficulty: Difficulty,
        xpAwarded: Long,
        leveledUp: Boolean,
        unlockedAchievement: Boolean,
    ): CompletionSound? {
        val positive = result == CompletionResult.COMPLETED || result == CompletionResult.PARTIAL
        // Skips/failures/reschedules are silent — no negative conditioning. So is
        // a zero-progress partial ("0 of 8 glasses"): recorded, but nothing won.
        if (!positive) return null
        if (result == CompletionResult.PARTIAL && fraction <= 0.0) return null

        // The biggest moment wins outright: a level-up (or a fresh achievement)
        // deserves its full celebration even on a trivial quest.
        if (leveledUp) return CompletionSound(CompletionChime.LEVEL_UP, 1f)
        if (unlockedAchievement) return CompletionSound(CompletionChime.TRIUMPH, 1f)

        if (result == CompletionResult.PARTIAL) {
            // A small bling for progress, gently louder as the quest nears done.
            val volume = (0.35f + 0.25f * fraction.toFloat()).coerceAtMost(0.6f)
            return CompletionSound(CompletionChime.PROGRESS, volume)
        }

        val chime = when (difficulty) {
            Difficulty.TRIVIAL, Difficulty.EASY -> CompletionChime.MINOR
            Difficulty.MEDIUM, Difficulty.HARD -> CompletionChime.MAJOR
            Difficulty.EPIC -> CompletionChime.TRIUMPH
        }
        // Louder with the value actually earned; floored so an anti-farm-capped
        // completion still gets a quiet acknowledgement, never silence.
        val volume = (MIN_COMPLETE_VOLUME + xpAwarded.coerceAtLeast(0L) / FULL_VOLUME_XP * (1f - MIN_COMPLETE_VOLUME))
            .coerceIn(MIN_COMPLETE_VOLUME, 1f)
        return CompletionSound(chime, volume)
    }
}
