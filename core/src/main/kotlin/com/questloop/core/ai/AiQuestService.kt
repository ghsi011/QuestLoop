package com.questloop.core.ai

import com.questloop.core.generation.QuestSchedule
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Raw quest shape we ask the model to return (tolerant defaults). */
@Serializable
internal data class AiQuestDto(
    val title: String = "",
    val category: String = "LIFE_ADMIN",
    val difficulty: String = "EASY",
    val priority: String = "NORMAL",
    val frequency: String = "ONE_OFF",
    val completionStyle: String = "BINARY",
    val estimatedMinutes: Int? = null,
    val targetCount: Int? = null,
    val unit: String? = null,
    val rationale: String? = null,
    /** "HH:MM" 24-hour times of day (recurring quests only); unparseable entries drop. */
    val scheduledTimes: List<String> = emptyList(),
    /** MONDAY..SUNDAY, only for WEEKLY quests when the user names a day. */
    val scheduledDayOfWeek: String? = null,
    /** 1..31, only for MONTHLY quests when the user names a day. */
    val scheduledDayOfMonth: Int? = null,
    /** Bounded runs: "for 5 days" -> 5, "for 12 months" -> 12. */
    val totalOccurrences: Int? = null,
    /** Off by default; the prompt allows it only for medication-style timing or an
     *  explicit "remind me" — and the user still reviews before saving. */
    val remindersEnabled: Boolean = false,
)

/**
 * Turns free-text todos/goals into structured quests via an [LlmClient], then
 * runs the model's output through the same [AiQuestValidator] guardrails as any
 * AI feature and falls back to the deterministic [FallbackSuggester] whenever the
 * model is unavailable, returns junk, or every suggestion is rejected (SPEC §5,
 * §11). The model never bypasses the safety guardrails.
 */
class AiQuestService(
    private val client: LlmClient,
    private val validator: AiQuestValidator = AiQuestValidator(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    data class Input(
        val todos: List<String> = emptyList(),
        val goals: List<String> = emptyList(),
        val focusAreas: List<QuestCategory> = emptyList(),
        val availableMinutes: Int = 120,
        val energy: Int? = null,
        val existing: List<Quest> = emptyList(),
    )

    /**
     * Result of a suggestion request. [fromAi] is false when the deterministic
     * fallback was used. [error] is non-null only when AI was actually attempted
     * and failed (transport error, unparseable output, or everything filtered),
     * so the caller can tell the user instead of silently echoing their text.
     * [errorDetail] is the raw transport failure behind a plain-copy [error] —
     * kept for the diagnostics log, never shown in the UI.
     */
    data class Suggestion(
        val quests: List<Quest>,
        val fromAi: Boolean,
        val error: String? = null,
        val errorDetail: String? = null,
    )

    suspend fun suggest(input: Input): Suggestion {
        val userPrompt = PromptLibrary.questGenerationUserPayload(
            availableMinutes = input.availableMinutes,
            energy = input.energy,
            todos = input.todos,
            goals = input.goals,
            focusAreas = input.focusAreas.map { it.name },
        ) + "\n\n" + SCHEMA_INSTRUCTION

        val attempt = runCatchingCancellable {
            client.complete(PromptLibrary.QUEST_GENERATION_SYSTEM, userPrompt)
        }
        if (attempt.isFailure) {
            val failure = describeFailure(attempt.exceptionOrNull())
            return fallback(input, failure.message, failure.detail)
        }

        val proposed = attempt.getOrNull()?.let(::parse)?.mapIndexedNotNull(::toQuest).orEmpty()
        val accepted = validator.validate(proposed, input.existing).accepted
        return when {
            accepted.isNotEmpty() -> Suggestion(accepted, fromAi = true)
            proposed.isEmpty() -> fallback(input, "AI returned an unexpected response.")
            else -> fallback(input, "AI suggestions didn't pass the safety checks.")
        }
    }

    /**
     * Breaks one free-text [goal] into a short ladder of reviewable quests, through
     * the same guardrails + deterministic fallback as [suggest]. Not persisted — the
     * caller reviews/edits before saving.
     */
    suspend fun decomposeGoal(goal: String, existing: List<Quest> = emptyList()): Suggestion {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) return Suggestion(emptyList(), fromAi = false, error = "Add a goal to break down.")
        val payload = PromptLibrary.goalDecompositionUserPayload(trimmed) + "\n\n" + SCHEMA_INSTRUCTION
        val attempt = runCatchingCancellable { client.complete(PromptLibrary.GOAL_DECOMPOSITION_SYSTEM, payload) }
        if (attempt.isFailure) {
            val failure = describeFailure(attempt.exceptionOrNull())
            return goalFallback(trimmed, failure.message, failure.detail)
        }
        val proposed = attempt.getOrNull()?.let(::parse)?.mapIndexedNotNull(::toQuest).orEmpty()
        val accepted = validator.validate(proposed, existing).accepted
        return when {
            accepted.isNotEmpty() -> Suggestion(accepted, fromAi = true)
            proposed.isEmpty() -> goalFallback(trimmed, "AI returned an unexpected response.")
            else -> goalFallback(trimmed, "AI suggestions didn't pass the safety checks.")
        }
    }

    private fun goalFallback(goal: String, error: String?, errorDetail: String? = null) = Suggestion(
        quests = FallbackSuggester.suggest(listOf("Make a start on: $goal"), emptySet()),
        fromAi = false,
        error = error,
        errorDetail = errorDetail,
    )

    /**
     * Turns one quick free-text [note] into EXACTLY ONE quest for the home-screen
     * widget's no-review quick-add, through the same guardrails + deterministic
     * fallback as [suggest]. Unlike [suggest], the prompt asks the model for a
     * single quest (and the result is capped at one), so the caller can persist it
     * directly without a review step.
     */
    suspend fun suggestOne(note: String, existing: List<Quest> = emptyList()): Suggestion {
        val trimmed = note.trim()
        if (trimmed.isBlank()) {
            return Suggestion(emptyList(), fromAi = false, error = "Add a few words to turn into a quest.")
        }
        val payload = PromptLibrary.questGenerationUserPayload(
            availableMinutes = 120,
            energy = null,
            todos = listOf(trimmed),
            goals = emptyList(),
            focusAreas = emptyList(),
        ) + "\n\n" + SINGLE_SCHEMA_INSTRUCTION
        val attempt = runCatchingCancellable { client.complete(PromptLibrary.QUICK_ADD_SYSTEM, payload) }
        if (attempt.isFailure) {
            val failure = describeFailure(attempt.exceptionOrNull())
            return oneFallback(trimmed, failure.message, failure.detail)
        }
        val proposed = attempt.getOrNull()?.let(::parse)?.mapIndexedNotNull(::toQuest).orEmpty()
        // Cap at one: even if the model over-produces, the widget adds a single quest.
        val accepted = validator.validate(proposed, existing).accepted.take(1)
        return when {
            accepted.isNotEmpty() -> Suggestion(accepted, fromAi = true)
            proposed.isEmpty() -> oneFallback(trimmed, "AI returned an unexpected response.")
            else -> oneFallback(trimmed, "AI suggestions didn't pass the safety checks.")
        }
    }

    private fun oneFallback(note: String, error: String?, errorDetail: String? = null) = Suggestion(
        quests = FallbackSuggester.suggest(listOf(note), emptySet()).take(1),
        fromAi = false,
        error = error,
        errorDetail = errorDetail,
    )

    private fun fallback(input: Input, error: String?, errorDetail: String? = null) = Suggestion(
        quests = FallbackSuggester.suggest(input.todos, input.focusAreas.toSet()),
        fromAi = false,
        error = error,
        errorDetail = errorDetail,
    )

    /** A failed call, split into user-facing copy and raw [detail] for the diagnostics log. */
    private class Failure(val message: String, val detail: String? = null)

    /**
     * Connectivity/timeout exceptions carry platform text ("Unable to resolve
     * host…", bare "timeout") that never belongs in the UI — map those to plain
     * copy and keep the raw exception for the diagnostics log. Everything else,
     * notably our [LlmClient]s' curated messages (provider error bodies, sign-in
     * prompts), keeps surfacing as-is.
     */
    private fun describeFailure(cause: Throwable?): Failure = when (cause) {
        is UnknownHostException, is SocketException, is SSLException,
        is EOFException, is InterruptedIOException ->
            Failure(CANT_REACH_AI, cause.toString())
        else -> {
            val reason = cause?.message?.takeIf { it.isNotBlank() } ?: "couldn't reach the model"
            Failure("AI request failed: $reason")
        }
    }

    /** Outcome of refining a quest: the revised quest, or an error reason (plus raw [errorDetail] for the log). */
    data class RefineResult(val quest: Quest?, val error: String? = null, val errorDetail: String? = null)

    /**
     * Revises one [quest] according to a free-text [instruction] (e.g. "make it
     * weekly and easier"), through the same guardrails. The revised quest keeps
     * the original id so the caller can replace it in place.
     */
    suspend fun refine(quest: Quest, instruction: String, existing: List<Quest> = emptyList()): RefineResult {
        if (instruction.isBlank()) return RefineResult(quest)
        val payload = PromptLibrary.questRefineUserPayload(
            currentQuestJson = json.encodeToString(AiQuestDto.serializer(), dtoFrom(quest)),
            instruction = instruction,
        ) + "\n\n" + SCHEMA_INSTRUCTION
        val attempt = runCatchingCancellable { client.complete(PromptLibrary.QUEST_REFINE_SYSTEM, payload) }
        if (attempt.isFailure) {
            val failure = describeFailure(attempt.exceptionOrNull())
            return RefineResult(null, failure.message, failure.detail)
        }
        val proposed = attempt.getOrNull()?.let(::parse)?.mapIndexedNotNull(::toQuest).orEmpty()
        val revised = validator.validate(proposed, existing).accepted.firstOrNull()
            ?: return RefineResult(null, "AI didn't return a usable revision.")
        return RefineResult(revised.copy(id = quest.id, origin = QuestOrigin.AI_SUGGESTED))
    }

    private fun dtoFrom(q: Quest) = AiQuestDto(
        title = q.title,
        category = q.category.name,
        difficulty = q.difficulty.name,
        priority = q.priority.name,
        frequency = q.frequency.name,
        completionStyle = q.completionStyle.name,
        estimatedMinutes = q.estimatedMinutes,
        targetCount = q.targetCount,
        unit = q.unit,
        rationale = q.rationale,
        // Round-trip the schedule so "remind me at 9 instead" style refinements work.
        scheduledTimes = q.scheduledTimes.map { "%02d:%02d".format(it / 60, it % 60) },
        scheduledDayOfWeek = q.scheduledDayOfWeek?.name,
        scheduledDayOfMonth = q.scheduledDayOfMonth,
        totalOccurrences = q.totalOccurrences,
        remindersEnabled = q.remindersEnabled,
    )

    /**
     * Extracts the JSON array from a possibly-chatty/markdown-fenced response.
     * Chatty preambles can themselves contain brackets ("Sure! [1] Here…"), so a
     * naive first-`[`…last-`]` slice would poison the whole batch — instead each
     * `[` is tried as a candidate start (with the matching `]` found by walking
     * the balance, string-aware) until one yields a USABLE quest. "Usable" (some
     * non-blank title) matters: every [AiQuestDto] field has a default, so any
     * array of objects — e.g. an echoed example or a "[{"step":1}]" plan — would
     * otherwise decode non-empty and hijack the result from the real array.
     * Candidates are pruned to arrays that can hold objects and capped so a
     * degenerate bracket-storm response can't pin the CPU inside the AI wake lock.
     */
    internal fun parse(raw: String): List<AiQuestDto> {
        var start = raw.indexOf('[')
        var attempts = 0
        while (start >= 0 && attempts < MAX_PARSE_CANDIDATES) {
            // The payload is an array of objects: skip candidates like "[1]" or
            // "[see below]" cheaply, without a balance walk or a decode attempt.
            var j = start + 1
            while (j < raw.length && raw[j].isWhitespace()) j++
            if (j >= raw.length || (raw[j] != '{' && raw[j] != ']')) {
                start = raw.indexOf('[', start + 1)
                continue
            }
            attempts++
            val end = matchingBracket(raw, start)
            if (end > start) {
                val decoded = runCatching {
                    json.decodeFromString(ListSerializer(AiQuestDto.serializer()), raw.substring(start, end + 1))
                }.getOrNull()
                if (decoded != null && decoded.any { it.title.isNotBlank() }) return decoded
            }
            start = raw.indexOf('[', start + 1)
        }
        return emptyList()
    }

    /**
     * Index of the `]` that closes the `[` at [start], skipping brackets inside
     * JSON string literals (and their escapes); -1 when unbalanced.
     */
    private fun matchingBracket(raw: String, start: Int): Int {
        var depth = 0
        var inString = false
        var i = start
        while (i < raw.length) {
            val c = raw[i]
            when {
                inString -> when (c) {
                    '\\' -> i++ // skip the escaped char
                    '"' -> inString = false
                }
                c == '"' -> inString = true
                c == '[' -> depth++
                c == ']' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun toQuest(index: Int, dto: AiQuestDto): Quest? {
        val title = dto.title.trim()
        if (title.isBlank()) return null
        val category = enumOrDefault(dto.category, QuestCategory.LIFE_ADMIN)
        val difficulty = enumOrDefault(dto.difficulty, Difficulty.EASY)
        val style = enumOrDefault(dto.completionStyle, CompletionStyle.BINARY)
        val quest = Quest(
            // Batch-unique id so suggestions from different calls can't collide on
            // their instance id (questId@day) if persisted directly.
            id = "ai-${java.util.UUID.randomUUID()}-$index",
            title = title,
            category = category,
            frequency = enumOrDefault(dto.frequency, QuestFrequency.ONE_OFF),
            difficulty = difficulty,
            priority = enumOrDefault(dto.priority, Priority.NORMAL),
            origin = QuestOrigin.AI_SUGGESTED,
            estimatedMinutes = (dto.estimatedMinutes ?: Quest.defaultMinutes(difficulty)).coerceIn(1, 240),
            isReductionQuest = category == QuestCategory.BAD_HABIT_REDUCTION,
            completionStyle = style,
            targetCount = if (style == CompletionStyle.QUANTITATIVE) (dto.targetCount ?: 1).coerceIn(1, 1000) else null,
            unit = if (style == CompletionStyle.QUANTITATIVE) dto.unit?.trim()?.ifBlank { null } else null,
            rationale = dto.rationale?.trim()?.ifBlank { null },
            scheduledTimes = dto.scheduledTimes.mapNotNull(::minuteOfDay),
            scheduledDayOfWeek = dto.scheduledDayOfWeek?.let {
                runCatching { java.time.DayOfWeek.valueOf(it.trim().uppercase()) }.getOrNull()
            },
            scheduledDayOfMonth = dto.scheduledDayOfMonth?.coerceIn(1, 31),
            totalOccurrences = dto.totalOccurrences?.coerceIn(1, 999),
            remindersEnabled = dto.remindersEnabled,
        )
        // Same canonicalisation as every persist path (times sorted/capped, anchors
        // matched to frequency, multi-time binary -> per-slot count), applied here
        // too so the review card already shows exactly what would be saved.
        return QuestSchedule.normalized(quest)
    }

    /** "8:30"/"08:30" -> minutes since midnight; null for anything else. */
    private fun minuteOfDay(raw: String): Int? {
        val match = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(raw) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name.trim().uppercase()) }.getOrDefault(default)

    companion object {
        /** Plain copy for connectivity failures; the raw exception rides along in [Suggestion.errorDetail]. */
        private const val CANT_REACH_AI = "Couldn't reach the AI. Check your connection and try again."

        /** Decode-attempt cap for [parse] — a real response has one payload array
         *  plus at most a few bracketed asides; beyond this it's garbage. */
        private const val MAX_PARSE_CANDIDATES = 20

        /** The object-shape spec shared by the multi- and single-quest instructions.
         *  The schedule rules ride along here (not only in the quest-design system
         *  prompt) because every call path — quick add, goal decomposition, refine —
         *  sends its own system prompt but shares this schema. */
        private val SCHEMA_BODY: String = buildString {
            appendLine("Respond with ONLY a JSON array (no prose, no markdown fences).")
            appendLine("Each element is an object:")
            appendLine("  {")
            appendLine("    \"title\": string,")
            appendLine("    \"category\": one of ${QuestCategory.entries.joinToString(", ") { it.name }},")
            appendLine("    \"difficulty\": one of ${Difficulty.entries.joinToString(", ") { it.name }} (sets XP),")
            appendLine("    \"priority\": one of ${Priority.entries.joinToString(", ") { it.name }},")
            appendLine("    \"frequency\": one of ${QuestFrequency.entries.joinToString(", ") { it.name }},")
            appendLine("    \"completionStyle\": one of ${CompletionStyle.entries.joinToString(", ") { it.name }},")
            appendLine("    \"estimatedMinutes\": integer (the target for DURATION),")
            appendLine("    \"targetCount\": integer (only for QUANTITATIVE),")
            appendLine("    \"unit\": short string (only for QUANTITATIVE, e.g. \"glasses\"),")
            appendLine("    \"scheduledTimes\": array of \"HH:MM\" 24-hour strings (recurring quests only),")
            appendLine("    \"scheduledDayOfWeek\": MONDAY..SUNDAY (WEEKLY only),")
            appendLine("    \"scheduledDayOfMonth\": integer 1-31 (MONTHLY only),")
            appendLine("    \"totalOccurrences\": integer (how many days/weeks/months in total),")
            appendLine("    \"remindersEnabled\": boolean,")
            appendLine("    \"rationale\": short string")
            appendLine("  }")
            appendLine("Schedule rules — set these ONLY when the user's wording gives them:")
            appendLine("- scheduledTimes for stated or clearly implied times of day (\"at 8\",")
            appendLine("  \"morning and evening\" -> [\"08:00\", \"20:00\"]). A DAILY quest may have up")
            appendLine("  to ${QuestSchedule.MAX_TIMES_PER_DAY} times (each becomes one check-off); WEEKLY/MONTHLY at most one.")
            appendLine("- scheduledDayOfWeek / scheduledDayOfMonth when a day is named")
            appendLine("  (\"every Monday\", \"rent on the 1st\").")
            appendLine("- totalOccurrences when the run is bounded: \"for 5 days\" -> 5 on a DAILY")
            appendLine("  quest, \"for 12 months\" -> 12 on a MONTHLY one.")
            appendLine("- remindersEnabled is false by DEFAULT. Set true ONLY for medication or")
            appendLine("  treatment that must happen on time, or when the user explicitly asks to")
            append("  be reminded/notified. It requires at least one scheduled time.")
        }

        val SCHEMA_INSTRUCTION: String = "$SCHEMA_BODY\nReturn at most 6 quests."

        /** Quick-add variant: force a single-element array so the widget adds one quest. */
        val SINGLE_SCHEMA_INSTRUCTION: String =
            "$SCHEMA_BODY\nReturn EXACTLY ONE quest (a single-element JSON array)."
    }
}
