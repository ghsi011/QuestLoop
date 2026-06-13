package com.questloop.core.reward

import kotlin.math.floor
import kotlin.math.pow
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

    /** Current level for a given cumulative XP total. */
    fun levelForXp(totalXp: Long): Int {
        require(totalXp >= 0) { "xp must be >= 0" }
        // Invert xpForLevel: solve BASE/2 * (L-1)L <= xp.
        // (L-1)L <= 2*xp/BASE  ->  L ~= (1 + sqrt(1 + 8*xp/BASE)) / 2
        val n = 2.0 * totalXp / BASE_STEP
        val l = floor((1.0 + sqrt(1.0 + 4.0 * n)) / 2.0).toInt()
        return l.coerceAtLeast(1)
    }

    /** Progress snapshot for UI / reviews. */
    data class LevelProgress(
        val level: Int,
        val xpIntoLevel: Long,
        val xpForNextLevel: Long,
        val fractionToNext: Double,
    )

    fun progress(totalXp: Long): LevelProgress {
        val level = levelForXp(totalXp)
        val floorXp = xpForLevel(level)
        val ceilXp = xpForLevel(level + 1)
        val span = (ceilXp - floorXp).coerceAtLeast(1)
        val into = totalXp - floorXp
        return LevelProgress(
            level = level,
            xpIntoLevel = into,
            xpForNextLevel = span,
            fractionToNext = (into.toDouble() / span.toDouble()).coerceIn(0.0, 1.0),
        )
    }
}

/** Helper used by power-curve experiments; kept internal for clarity in tests. */
internal fun quadraticInverse(value: Double): Double = value.pow(0.5)
