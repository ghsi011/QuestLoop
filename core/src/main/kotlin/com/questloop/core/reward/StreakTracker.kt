package com.questloop.core.reward

/**
 * Computes streak length from the set of days the user was "active" (completed
 * at least one meaningful quest), allowing a configurable number of grace days
 * so a single missed day doesn't erase weeks of progress (SPEC 8 & 9).
 */
object StreakTracker {

    /**
     * @param activeEpochDays days on which the user completed >=1 qualifying quest.
     * @param today the day to evaluate the streak as of.
     * @param graceDays how many consecutive missed days are tolerated within a streak.
     * @return current streak length counting back from [today].
     */
    fun currentStreak(
        activeEpochDays: Set<Long>,
        today: Long,
        graceDays: Int = 1,
    ): Int {
        require(graceDays >= 0)
        // No active days at all (or all in the future): no streak as of today.
        val earliest = activeEpochDays.minOrNull() ?: return 0

        // Walk backwards from today. An active day extends the streak and resets
        // the miss counter; a gap consumes grace. More than [graceDays] consecutive
        // misses ends the run. Leading misses (today not done yet) behave the same:
        // streak is still 0 while we walk to the most recent active day, so they
        // can't break a streak that exists just behind them.
        var streak = 0
        var missesInARow = 0
        var cursor = today
        while (cursor >= earliest) {
            if (activeEpochDays.contains(cursor)) {
                streak++
                missesInARow = 0
            } else {
                missesInARow++
                if (missesInARow > graceDays) break
            }
            cursor--
        }
        return streak
    }

    /** Longest run of consecutive active days ever recorded (grace not applied). */
    fun longestStreak(activeEpochDays: Set<Long>): Int {
        if (activeEpochDays.isEmpty()) return 0
        val sorted = activeEpochDays.sorted()
        var longest = 1
        var current = 1
        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) {
                current++
                longest = maxOf(longest, current)
            } else {
                current = 1
            }
        }
        return longest
    }

    /** True if completing a quest today would extend (vs. restart) the streak. */
    fun isStreakAlive(activeEpochDays: Set<Long>, today: Long, graceDays: Int = 1): Boolean {
        val mostRecent = activeEpochDays.maxOrNull() ?: return false
        return (today - mostRecent) <= (graceDays + 1)
    }
}
