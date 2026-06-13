package com.questloop.core.generation

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin

/**
 * Generates the small set of admin "routine" quests that form QuestLoop's
 * minimal daily interaction loop.
 *
 * The design goal (per product direction): the app should need only a couple of
 * minutes a day. The backbone is a tiny morning check-in and a short evening
 * wrap-up — everything else is optional. These are META_MAINTENANCE quests, so
 * the reward economy already keeps them lightly rewarded and capped; they exist
 * to build the habit of tending the system, not to inflate progress.
 *
 * IDs are stable per day so completing one removes it for the rest of the day.
 */
object RoutineQuestFactory {

    const val MORNING_REVIEW = "routine-morning-review"
    const val EVENING_REVIEW = "routine-evening-review"
    const val EVENING_INTAKE = "routine-evening-intake"

    fun routinesFor(dayPart: DayPart): List<Quest> = when (dayPart) {
        DayPart.MORNING -> listOf(morningReview())
        DayPart.MIDDAY -> emptyList()
        DayPart.EVENING -> listOf(eveningReview(), eveningIntake())
    }

    /**
     * Morning micro-quest: glance at today's open quests. Instantly completable
     * for an early, honest dopamine hit that costs ~30 seconds.
     */
    private fun morningReview() = Quest(
        id = MORNING_REVIEW,
        title = "Review today's open quests",
        category = QuestCategory.META_MAINTENANCE,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.TRIVIAL,
        origin = QuestOrigin.SYSTEM_RECURRING,
        estimatedMinutes = 1,
        completionStyle = CompletionStyle.BINARY,
        rationale = "A 30-second glance to start the day. Mark it done right away.",
    )

    /** Evening admin 1: review and mark off what you completed today. */
    private fun eveningReview() = Quest(
        id = EVENING_REVIEW,
        title = "Mark off what you completed today",
        category = QuestCategory.META_MAINTENANCE,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.TRIVIAL,
        origin = QuestOrigin.SYSTEM_RECURRING,
        estimatedMinutes = 1,
        completionStyle = CompletionStyle.BINARY,
        rationale = "Close the loop on today's wins.",
    )

    /**
     * Evening admin 2: capture new todos and a quick habit/health check-in.
     * Subjective by design — it's about showing up for ~2 minutes, not a score.
     */
    private fun eveningIntake() = Quest(
        id = EVENING_INTAKE,
        title = "Add new todos + quick check-in",
        category = QuestCategory.META_MAINTENANCE,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.EASY,
        origin = QuestOrigin.SYSTEM_RECURRING,
        estimatedMinutes = 2,
        completionStyle = CompletionStyle.SUBJECTIVE,
        rationale = "Brain-dump tomorrow's todos and jot a line on how habits/health felt.",
    )
}
