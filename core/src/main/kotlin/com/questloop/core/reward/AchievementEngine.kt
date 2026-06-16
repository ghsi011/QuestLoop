package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.QuestCategory

/**
 * Achievements / badges (SPEC §6 & §10 MVP "basic achievements").
 *
 * Pure and data-driven: each [Achievement] has a predicate over a
 * [ProgressStats] snapshot. Evaluation is deterministic so the unlock set is
 * reproducible and testable.
 */
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val predicate: (ProgressStats) -> Boolean,
)

/** Aggregated, side-effect-free view of progress used to evaluate achievements. */
data class ProgressStats(
    val totalCompleted: Int,
    val level: Int,
    val longestStreak: Int,
    val distinctCategories: Int,
    val honestyLogs: Int,
    val reductionWins: Int,
) {
    companion object {
        fun from(records: List<CompletionRecord>, totalXp: Long, longestStreak: Int): ProgressStats {
            // A zero-progress partial carries no penalty but isn't a "completion"
            // for achievement purposes — only real progress counts (SPEC 8).
            val completed = records.filter { it.countsAsActivity }
            return ProgressStats(
                totalCompleted = completed.size,
                level = LevelSystem.levelForXp(totalXp),
                longestStreak = longestStreak,
                distinctCategories = completed.map { it.category }.toSet().size,
                honestyLogs = records.count {
                    it.category == QuestCategory.BAD_HABIT_REDUCTION &&
                        (it.result == CompletionResult.FAILED || it.result == CompletionResult.SKIPPED)
                },
                reductionWins = completed.count { it.category == QuestCategory.BAD_HABIT_REDUCTION },
            )
        }
    }
}

object AchievementEngine {

    val ALL: List<Achievement> = listOf(
        Achievement("first_steps", "First Steps", "Complete your first quest.") { it.totalCompleted >= 1 },
        Achievement("getting_going", "Getting Going", "Complete 10 quests.") { it.totalCompleted >= 10 },
        Achievement("centurion", "Centurion", "Complete 100 quests.") { it.totalCompleted >= 100 },
        Achievement("consistent", "Consistent", "Reach a 3-day streak.") { it.longestStreak >= 3 },
        Achievement("week_warrior", "Week Warrior", "Reach a 7-day streak.") { it.longestStreak >= 7 },
        Achievement("level_5", "Apprentice", "Reach level 5.") { it.level >= 5 },
        Achievement("level_10", "Adventurer", "Reach level 10.") { it.level >= 10 },
        Achievement("well_rounded", "Well-Rounded", "Complete quests in 5 different categories.") {
            it.distinctCategories >= 5
        },
        Achievement("honest_tracker", "Honest Tracker", "Log a tough day 5 times — honesty counts.") {
            it.honestyLogs >= 5
        },
        Achievement("breaking_free", "Breaking Free", "Win 10 bad-habit-reduction quests.") {
            it.reductionWins >= 10
        },
    )

    /** Returns the achievements currently unlocked for the given stats. */
    fun unlocked(stats: ProgressStats): List<Achievement> = ALL.filter { it.predicate(stats) }

    /** Achievements unlocked by [after] that were not unlocked by [before]. */
    fun newlyUnlocked(before: ProgressStats, after: ProgressStats): List<Achievement> {
        val had = unlocked(before).map { it.id }.toSet()
        return unlocked(after).filterNot { it.id in had }
    }
}
