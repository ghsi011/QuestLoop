package com.questloop.core.generation

import com.questloop.core.model.BadHabit
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin

/**
 * Turns the user's habits and bad habits into recurring quests so they feed the
 * daily plan (SPEC §3–4). Pure and deterministic; ids are stable and namespaced
 * so completion history and recurrence scheduling work the same as for any quest.
 */
object HabitQuestFactory {

    const val HABIT_PREFIX = "habit-"
    const val BAD_HABIT_PREFIX = "badhabit-"
    const val GOAL_PREFIX = "goal-"

    fun fromHabit(habit: Habit): Quest {
        // Habits aimed at most days of the week recur daily; less frequent goals
        // recur weekly so they aren't prompted every single day.
        val daily = habit.targetPerWeek >= 5
        // A weekly habit with a multi-occurrence target ("swim 3× a week") counts
        // each occurrence toward the weekly target: QUANTITATIVE progress
        // accumulates across the calendar week, whereas a BINARY weekly quest
        // hides for a whole week after one completion and could never meet its
        // own advertised "N×/week".
        val counted = !daily && habit.targetPerWeek >= 2
        return Quest(
            id = "$HABIT_PREFIX${habit.id}",
            title = habit.title,
            category = habit.category,
            frequency = if (daily) QuestFrequency.DAILY else QuestFrequency.WEEKLY,
            difficulty = habit.difficulty,
            origin = QuestOrigin.SYSTEM_RECURRING,
            completionStyle = if (counted) CompletionStyle.QUANTITATIVE else CompletionStyle.BINARY,
            targetCount = if (counted) habit.targetPerWeek else null,
            unit = if (counted) "times" else null,
            rationale = "Building a habit — aim for ${habit.targetPerWeek}× a week.",
        )
    }

    fun fromBadHabit(badHabit: BadHabit): Quest = Quest(
        id = "$BAD_HABIT_PREFIX${badHabit.id}",
        title = "Stay on track: ${badHabit.title}",
        category = QuestCategory.BAD_HABIT_REDUCTION,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
        origin = QuestOrigin.SYSTEM_RECURRING,
        isReductionQuest = true,
        completionStyle = CompletionStyle.BINARY,
        rationale = badHabit.dailyLimit
            ?.let { "Aim to stay under $it today. Honest tracking counts either way." }
            ?: "Track honestly today — recovery beats perfection.",
    )

    /**
     * A goal becomes a gentle weekly check-in quest: progress on a long-term goal
     * is fuzzy, so it's SUBJECTIVE (rate how it went) rather than pass/fail.
     */
    fun fromGoal(goal: Goal): Quest = Quest(
        id = "$GOAL_PREFIX${goal.id}",
        title = "Make progress: ${goal.title}",
        category = goal.category,
        frequency = QuestFrequency.WEEKLY,
        difficulty = Difficulty.MEDIUM,
        origin = QuestOrigin.SYSTEM_RECURRING,
        completionStyle = CompletionStyle.SUBJECTIVE,
        rationale = "A step toward your goal — rate how it went; showing up counts.",
    )

    /** All quests derived from a user's habits, bad habits, and goals. */
    fun deriveAll(
        habits: List<Habit>,
        badHabits: List<BadHabit>,
        goals: List<Goal> = emptyList(),
    ): List<Quest> =
        habits.map(::fromHabit) + badHabits.map(::fromBadHabit) + goals.map(::fromGoal)
}
