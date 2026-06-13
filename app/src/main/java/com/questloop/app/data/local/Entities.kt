package com.questloop.app.data.local

import androidx.room.Entity
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
)

@Entity(tableName = "completions")
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
