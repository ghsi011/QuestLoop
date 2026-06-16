package com.questloop.core.reward

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Maps cumulative XP to a level using a smooth quadratic curve.
 *
 * The amount of XP required to go from level L to L+1 grows linearly, so total
 * XP for a level grows quadratically. This keeps early levels fast (encouraging)
 * and later levels meaningful, without ever requiring a lookup table.
 *
 * xpForLevel(L) = BASE * (L-1) * L / 2      (total XP needed to *reach* level L)
 */
object LevelSystem {

    /** XP that the first level-up costs; subsequent gaps grow by this each level. */
    const val BASE_STEP = 100.0

    /** Total cumulative XP required to have reached [level] (level >= 1). */
    fun xpForLevel(level: Int): Long {
        require(level >= 1) { "level must be >= 1" }
        val l = (level - 1).toDouble()
        return floor(BASE_STEP * l * (l + 1) / 2.0).toLong()
    }

    /**
     * Current level for a given cumulative XP total. A gentle miss penalty can
     * briefly push the ledger below zero (e.g. a skip before any XP is earned);
     * such totals simply map to level 1 rather than being rejected.
     */
    fun levelForXp(totalXp: Long): Int {
        val xp = totalXp.coerceAtLeast(0)
        // Invert xpForLevel: solve BASE/2 * (L-1)L <= xp.
        // (L-1)L <= 2*xp/BASE  ->  L ~= (1 + sqrt(1 + 8*xp/BASE)) / 2
        val n = 2.0 * xp / BASE_STEP
        var l = floor((1.0 + sqrt(1.0 + 4.0 * n)) / 2.0).toInt().coerceAtLeast(1)
        // Correct any floating-point drift against the exact integer thresholds
        // so the result is always the largest L with xpForLevel(L) <= xp.
        while (xpForLevel(l + 1) <= xp) l++
        while (l > 1 && xpForLevel(l) > xp) l--
        return l
    }

    /** Progress snapshot for UI / reviews. */
    data class LevelProgress(
        val level: Int,
        val xpIntoLevel: Long,
        val xpForNextLevel: Long,
        val fractionToNext: Double,
    )

    fun progress(totalXp: Long): LevelProgress {
        val xp = totalXp.coerceAtLeast(0)
        val level = levelForXp(xp)
        val floorXp = xpForLevel(level)
        val ceilXp = xpForLevel(level + 1)
        val span = (ceilXp - floorXp).coerceAtLeast(1)
        val into = xp - floorXp
        return LevelProgress(
            level = level,
            xpIntoLevel = into,
            xpForNextLevel = span,
            fractionToNext = (into.toDouble() / span.toDouble()).coerceIn(0.0, 1.0),
        )
    }
}
