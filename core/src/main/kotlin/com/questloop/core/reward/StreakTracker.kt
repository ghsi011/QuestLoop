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
        if (activeEpochDays.isEmpty()) return 0

        // If today isn't active yet, the streak is still alive as long as the
        // most recent active day is within the grace window.
        var streak = 0
        var cursor = today
        var missesInARow = 0
        // Allow the streak to "start" from the most recent active day within grace.
        // Walk backwards day by day.
        var startedCounting = false

        while (cursor >= (activeEpochDays.minOrNull() ?: cursor)) {
            if (activeEpochDays.contains(cursor)) {
                streak++
                missesInARow = 0
                startedCounting = true
            } else {
                if (!startedCounting) {
                    // Leading missed days (e.g. today not done yet) consume grace
                    // but don't break a streak that exists just behind them.
                    missesInARow++
                    if (missesInARow > graceDays) break
                } else {
                    missesInARow++
                    if (missesInARow > graceDays) break
                }
            }
            cursor--
        }
        return streak
    }

    /** True if completing a quest today would extend (vs. restart) the streak. */
    fun isStreakAlive(activeEpochDays: Set<Long>, today: Long, graceDays: Int = 1): Boolean {
        val mostRecent = activeEpochDays.maxOrNull() ?: return false
        return (today - mostRecent) <= (graceDays + 1)
    }
}
