package com.questloop.app.data

import com.questloop.app.data.local.CompletionEntity
import com.questloop.app.data.local.QuestEntity
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import com.questloop.core.model.VerificationMethod

// Enum parsing is uniformly tolerant of unknown stored values (forward/backward
// compatibility), falling back to a sensible default rather than crashing.
private inline fun <reified T : Enum<T>> parseEnum(name: String, default: T): T =
    runCatching { enumValueOf<T>(name) }.getOrDefault(default)

fun QuestEntity.toModel(): Quest = Quest(
    id = id,
    title = title,
    category = parseEnum(category, QuestCategory.LIFE_ADMIN),
    frequency = parseEnum(frequency, QuestFrequency.ONE_OFF),
    difficulty = parseEnum(difficulty, Difficulty.MEDIUM),
    priority = parseEnum(priority, Priority.NORMAL),
    origin = parseEnum(origin, QuestOrigin.USER_CREATED),
    estimatedMinutes = estimatedMinutes,
    deadlineEpochDay = deadlineEpochDay,
    isReductionQuest = isReductionQuest,
    completionStyle = parseEnum(completionStyle, CompletionStyle.BINARY),
    targetCount = targetCount,
    unit = unit,
    tags = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() },
    rationale = rationale,
    allowOverCompletion = allowOverCompletion,
    scheduledTimes = if (scheduledTimes.isBlank()) {
        emptyList()
    } else {
        scheduledTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
    },
    // Tolerant like the enum parsing: an out-of-range stored value degrades to
    // "no anchor" rather than crashing (DayOfWeek.of throws outside 1..7).
    scheduledDayOfWeek = scheduledDayOfWeek?.let { iso -> runCatching { java.time.DayOfWeek.of(iso) }.getOrNull() },
    scheduledDayOfMonth = scheduledDayOfMonth,
    totalOccurrences = totalOccurrences,
    remindersEnabled = remindersEnabled,
    countsTimeSlots = countsTimeSlots,
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
    completionStyle = completionStyle.name,
    targetCount = targetCount,
    unit = unit,
    tags = tags.joinToString(","),
    rationale = rationale,
    archived = archived,
    allowOverCompletion = allowOverCompletion,
    scheduledTimes = scheduledTimes.joinToString(","),
    scheduledDayOfWeek = scheduledDayOfWeek?.value,
    scheduledDayOfMonth = scheduledDayOfMonth,
    totalOccurrences = totalOccurrences,
    remindersEnabled = remindersEnabled,
    countsTimeSlots = countsTimeSlots,
)

fun CompletionEntity.toModel(): CompletionRecord = CompletionRecord(
    instanceId = instanceId,
    questId = questId,
    category = parseEnum(category, QuestCategory.LIFE_ADMIN),
    difficulty = parseEnum(difficulty, Difficulty.MEDIUM),
    priority = parseEnum(priority, Priority.NORMAL),
    result = parseEnum(result, CompletionResult.COMPLETED),
    verification = parseEnum(verification, VerificationMethod.MANUAL),
    epochDay = epochDay,
    fraction = fraction,
    isMeta = isMeta,
    xpAwarded = xpAwarded,
)

fun CompletionRecord.toEntity(): CompletionEntity = CompletionEntity(
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
