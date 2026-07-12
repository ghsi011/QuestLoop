package com.questloop.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted quest definition. Enums are stored as their stable [Enum.name] so
 * the DB stays human-readable and resilient to ordinal reordering.
 */
@Entity(tableName = "quests")
data class QuestEntity(
    @PrimaryKey val id: String,
    val title: String,
    val category: String,
    val frequency: String,
    val difficulty: String,
    val priority: String,
    val origin: String,
    val estimatedMinutes: Int,
    val deadlineEpochDay: Long?,
    val isReductionQuest: Boolean,
    val completionStyle: String = "BINARY",
    val targetCount: Int? = null,
    val unit: String? = null,
    val tags: String, // comma-separated
    val rationale: String?,
    val archived: Boolean = false,
    /** Measured quests only: allow logging past the target for the interval (v3+). */
    val allowOverCompletion: Boolean = false,
    /** Comma-separated minutes-of-day (0..1439) the quest is scheduled at (v5+). */
    val scheduledTimes: String = "",
    /** WEEKLY anchor as ISO day-of-week 1..7 (Mon..Sun), null = rolling (v5+). */
    val scheduledDayOfWeek: Int? = null,
    /** MONTHLY anchor day 1..31, null = rolling (v5+). */
    val scheduledDayOfMonth: Int? = null,
    /** Retire after this many completed intervals; null = no limit (v5+). */
    val totalOccurrences: Int? = null,
    /** Per-quest reminder notifications at the scheduled times (v5+). */
    val remindersEnabled: Boolean = false,
)

/** Projection: the last fully-completed day for a quest (recurrence scheduling). */
data class LastCompletion(
    val questId: String,
    val lastDay: Long,
)

/** Projection: one fully-completed record's day (occurrence-limit counting). */
data class CompletedDay(
    val questId: String,
    val epochDay: Long,
)

// The ledger grows without bound and each Today refresh runs ~10 windowed/
// aggregate queries over it (epochDay windows, GROUP BY questId, result-filtered
// counts) — index those columns so the queries don't full-scan the table (v4+).
@Entity(
    tableName = "completions",
    indices = [Index("epochDay"), Index("questId"), Index("result")],
)
data class CompletionEntity(
    @PrimaryKey val instanceId: String,
    val questId: String,
    val category: String,
    val difficulty: String,
    val priority: String,
    val result: String,
    val verification: String,
    val epochDay: Long,
    val fraction: Double,
    val isMeta: Boolean,
    val xpAwarded: Long,
)
