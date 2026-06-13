package com.questloop.app.data

import com.questloop.app.data.local.CompletionEntity
import com.questloop.app.data.local.QuestEntity
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import com.questloop.core.model.VerificationMethod

fun QuestEntity.toModel(): Quest = Quest(
    id = id,
    title = title,
    category = QuestCategory.valueOf(category),
    frequency = QuestFrequency.valueOf(frequency),
    difficulty = Difficulty.valueOf(difficulty),
    priority = Priority.valueOf(priority),
    origin = QuestOrigin.valueOf(origin),
    estimatedMinutes = estimatedMinutes,
    deadlineEpochDay = deadlineEpochDay,
    isReductionQuest = isReductionQuest,
    tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
    rationale = rationale,
)

fun Quest.toEntity(archived: Boolean = false): QuestEntity = QuestEntity(
    id = id,
    title = title,
    category = category.name,
    frequency = frequency.name,
    difficulty = difficulty.name,
    priority = priority.name,
    origin = origin.name,
    estimatedMinutes = estimatedMinutes,
    deadlineEpochDay = deadlineEpochDay,
    isReductionQuest = isReductionQuest,
    tags = tags.joinToString(","),
    rationale = rationale,
    archived = archived,
)

fun CompletionEntity.toModel(): CompletionRecord = CompletionRecord(
    instanceId = instanceId,
    questId = questId,
    category = QuestCategory.valueOf(category),
    difficulty = Difficulty.valueOf(difficulty),
    priority = Priority.valueOf(priority),
    result = CompletionResult.valueOf(result),
    verification = VerificationMethod.valueOf(verification),
    epochDay = epochDay,
    fraction = fraction,
    isMeta = isMeta,
)

fun CompletionRecord.toEntity(xpAwarded: Long): CompletionEntity = CompletionEntity(
    instanceId = instanceId,
    questId = questId,
    category = category.name,
    difficulty = difficulty.name,
    priority = priority.name,
    result = result.name,
    verification = verification.name,
    epochDay = epochDay,
    fraction = fraction,
    isMeta = isMeta,
    xpAwarded = xpAwarded,
)
