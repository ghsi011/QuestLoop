package com.questloop.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.DayOfWeek

/**
 * Serializes a [DayOfWeek] as its ISO number (1=Monday … 7=Sunday) so the
 * exported/persisted profile stays a stable primitive across app versions,
 * independent of enum ordering.
 */
object IsoDayOfWeekSerializer : KSerializer<DayOfWeek> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("DayOfWeek", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: DayOfWeek) = encoder.encodeInt(value.value)

    // Degrade a corrupt/out-of-range value (a hand-edited backup) to Sunday rather
    // than throw — matching ProfileStore's read path, so one bad field can't reject
    // an entire otherwise-valid import.
    override fun deserialize(decoder: Decoder): DayOfWeek =
        runCatching { DayOfWeek.of(decoder.decodeInt()) }.getOrDefault(DayOfWeek.SUNDAY)
}

/**
 * Core domain model for QuestLoop.
 *
 * Everything in this package is pure data (no Android, no I/O) so that the
 * reward economy, quest generation, completion tracking and safety rules can be
 * unit-tested deterministically on a plain JVM.
 */

/** High-level area of life a quest belongs to (SPEC section 4). */
@Serializable
enum class QuestCategory {
    HEALTH,
    LIFE_ADMIN,
    CHORES,
    WORK_STUDY,
    SOCIAL,
    PERSONAL_GROWTH,
    HOBBY_MAINTENANCE,
    BAD_HABIT_REDUCTION,
    META_MAINTENANCE;

    /** Meta-maintenance is rewarded lightly so it can never dominate progression. */
    val isMeta: Boolean get() = this == META_MAINTENANCE
}

/** How often a quest recurs (SPEC section 4). */
@Serializable
enum class QuestFrequency {
    DAILY,
    WEEKLY,
    MONTHLY,
    RECURRING,
    ONE_OFF,
    SEASONAL;
}

/**
 * Difficulty / effort tier. Drives base XP and the suggested reward weighting.
 * Kept to a small ordinal scale so users and the AI reason about it consistently.
 */
@Serializable
enum class Difficulty(val baseXp: Int, val weight: Double) {
    TRIVIAL(5, 0.5),
    EASY(10, 1.0),
    MEDIUM(20, 2.0),
    HARD(35, 3.5),
    EPIC(60, 6.0);

    /**
     * Low-effort tiers (trivial/easy) are the farmable ones: many quick quests in
     * a day can otherwise out-earn real progress. The reward engine caps their
     * combined daily XP (SPEC 6 & 8 anti-farming).
     */
    val isLowEffort: Boolean get() = this == TRIVIAL || this == EASY
}

/** User-facing importance, independent of difficulty (an easy task can be critical). */
@Serializable
enum class Priority(val multiplier: Double) {
    LOW(0.8),
    NORMAL(1.0),
    HIGH(1.3),
    CRITICAL(1.6);
}

/** Where a quest came from. Affects trust / anti-abuse handling. */
@Serializable
enum class QuestOrigin {
    USER_CREATED,
    AI_SUGGESTED,
    SYSTEM_RECURRING;
}

@Serializable
enum class CompletionResult {
    COMPLETED,
    PARTIAL,
    SKIPPED,
    FAILED,
    RESCHEDULED;
}

/** How a completion was verified (SPEC section 8). */
@Serializable
enum class VerificationMethod {
    MANUAL,
    TIMER,
    CHECKLIST,
    CALENDAR,
    LOCATION,
    INTEGRATION;
}

/**
 * How "done" is defined for a quest (SPEC §8: partial completion, subjective
 * tasks). Not every quest is binary; this lets the app collect the right kind of
 * completion input and lets the reward engine credit progress fairly.
 */
@Serializable
enum class CompletionStyle {
    /** Simple done / not-done. */
    BINARY,

    /** A count toward a target (e.g. 6 of 8 glasses of water). */
    QUANTITATIVE,

    /** Time spent toward a target duration (e.g. 30 of 50 focus minutes). */
    DURATION,

    /** Self-rated effort/progress for fuzzy goals (e.g. "made progress writing"). */
    SUBJECTIVE;
}

/** Part of the day, used to surface the right minimal-interaction routine. */
@Serializable
enum class DayPart {
    MORNING,
    MIDDAY,
    EVENING;

    companion object {
        /** Maps a 0..23 hour to a day part. Morning < 12, evening >= 17. */
        fun fromHour(hour: Int): DayPart = when (hour) {
            in 0..11 -> MORNING
            in 12..16 -> MIDDAY
            else -> EVENING
        }
    }
}

/**
 * A single quest definition. `id` is stable across recurrences; a recurring
 * quest produces many [QuestInstance]s over time.
 */
@Serializable
data class Quest(
    val id: String,
    val title: String,
    val category: QuestCategory,
    val frequency: QuestFrequency,
    val difficulty: Difficulty,
    val priority: Priority = Priority.NORMAL,
    val origin: QuestOrigin = QuestOrigin.USER_CREATED,
    /** Estimated minutes to complete; used for daily time budgeting. */
    val estimatedMinutes: Int = defaultMinutes(difficulty),
    /** Optional deadline as epoch-day; null = no hard deadline. */
    val deadlineEpochDay: Long? = null,
    /** For bad-habit-reduction quests, success = NOT doing the thing. */
    val isReductionQuest: Boolean = category == QuestCategory.BAD_HABIT_REDUCTION,
    /** How completion is defined/measured for this quest (SPEC §8). */
    val completionStyle: CompletionStyle = CompletionStyle.BINARY,
    /** Target for QUANTITATIVE quests (e.g. 8 glasses); null otherwise. */
    val targetCount: Int? = null,
    /** Unit label for QUANTITATIVE quests (e.g. "glasses", "pages"). */
    val unit: String? = null,
    /**
     * For measured (QUANTITATIVE/DURATION) quests: allow logging *past* the target
     * (e.g. a 3rd swim on a "swim 2×/week"). When true the quest stays loggable for
     * the whole interval instead of leaving the list at the target; extra effort
     * earns a small, diminishing bonus (SPEC §8, still anti-farm bounded).
     */
    val allowOverCompletion: Boolean = false,
    val tags: List<String> = emptyList(),
    /** Optional short rationale shown to the user (AI explainability, SPEC 5). */
    val rationale: String? = null,
) {
    companion object {
        fun defaultMinutes(d: Difficulty): Int = when (d) {
            Difficulty.TRIVIAL -> 2
            Difficulty.EASY -> 10
            Difficulty.MEDIUM -> 25
            Difficulty.HARD -> 50
            Difficulty.EPIC -> 120
        }
    }
}

/** A scheduled occurrence of a [Quest] on a particular day. */
@Serializable
data class QuestInstance(
    val instanceId: String,
    val quest: Quest,
    val scheduledEpochDay: Long,
)

/** A recorded outcome for a quest instance. */
@Serializable
data class CompletionRecord(
    val instanceId: String,
    val questId: String,
    val category: QuestCategory,
    val difficulty: Difficulty,
    val priority: Priority,
    val result: CompletionResult,
    val verification: VerificationMethod = VerificationMethod.MANUAL,
    val epochDay: Long,
    /** 0.0..1.0 for PARTIAL completion; 1.0 for COMPLETED. */
    val fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
    val isMeta: Boolean = category.isMeta,
    /** XP this record was granted (signed). Used to derive totals and per-day caps. */
    val xpAwarded: Long = 0,
) {
    /**
     * Whether this record counts as a day's "activity" for streaks, consistency
     * and achievements. A genuinely zero-progress partial (e.g. "0 of 8 glasses")
     * is recorded as PARTIAL so it carries no penalty, but it must NOT prop up a
     * streak — only real progress does (SPEC 8: never punish, never game).
     */
    val countsAsActivity: Boolean
        get() = result == CompletionResult.COMPLETED ||
            (result == CompletionResult.PARTIAL && fraction > 0.0)
}

/** A good habit the user wants to build. */
@Serializable
data class Habit(
    val id: String,
    val title: String,
    val category: QuestCategory,
    val difficulty: Difficulty = Difficulty.EASY,
    val targetPerWeek: Int = 7,
)

/** A bad habit the user wants to reduce (SPEC section 4, honesty-first). */
@Serializable
data class BadHabit(
    val id: String,
    val title: String,
    /** Self-set ceiling per day the user is trying to stay under (e.g. cigarettes). */
    val dailyLimit: Int? = null,
)

@Serializable
data class Goal(
    val id: String,
    val title: String,
    val category: QuestCategory,
    val targetEpochDay: Long? = null,
)

/** Lightweight daily check-in (SPEC section 7). */
@Serializable
data class EnergyCheckIn(
    val epochDay: Long,
    /** 1 (depleted) .. 5 (energized). */
    val energy: Int,
    /** Minutes the user actually has available today. */
    val availableMinutes: Int,
)

/** User preferences that shape generation, rewards and safety. */
@Serializable
data class UserPreferences(
    /** Soft cap on how many quests appear in a single daily list. */
    val maxDailyQuests: Int = 6,
    /** Default minutes/day available when no check-in is provided. */
    val defaultAvailableMinutes: Int = 120,
    /** Whether the user opted in to sensitive details in notifications (SPEC 9). */
    val sensitiveNotificationsOptIn: Boolean = false,
    /** User-defined affordable monthly reward budget cap, in their currency. */
    val monthlyRewardBudgetCap: Double = 0.0,
    /** Streak grace days before a streak breaks (compassionate failure). */
    val streakGraceDays: Int = 1,
    /** Categories the user wants to emphasise. */
    val focusCategories: Set<QuestCategory> = emptySet(),
    /** Opt-in: use the device calendar's free time as today's time budget (SPEC §10). */
    val calendarBudgetEnabled: Boolean = false,
    /**
     * The day the user's week starts on. Defaults to Sunday (the Jewish/US week).
     * Anchors weekly-quest interval resets (`questId@weekStart`) and the "this
     * week" review/history windows. Changing it re-anchors the *current* week: a
     * weekly quest logged earlier in the old week keys to a different slot, so it
     * can reappear at 0 and be logged again (a known cost, like a timezone change;
     * no progress migration).
     */
    @Serializable(with = IsoDayOfWeekSerializer::class)
    val firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
)

@Serializable
data class UserProfile(
    val totalXp: Long = 0,
    val preferences: UserPreferences = UserPreferences(),
    val goals: List<Goal> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val badHabits: List<BadHabit> = emptyList(),
)
