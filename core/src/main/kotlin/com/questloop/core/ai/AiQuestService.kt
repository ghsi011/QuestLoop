package com.questloop.core.ai

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Raw quest shape we ask the model to return (tolerant defaults). */
@Serializable
internal data class AiQuestDto(
    val title: String = "",
    val category: String = "LIFE_ADMIN",
    val difficulty: String = "EASY",
    val estimatedMinutes: Int? = null,
    val rationale: String? = null,
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
     */
    data class Suggestion(val quests: List<Quest>, val fromAi: Boolean, val error: String? = null)

    suspend fun suggest(input: Input): Suggestion {
        val userPrompt = PromptLibrary.questGenerationUserPayload(
            availableMinutes = input.availableMinutes,
            energy = input.energy,
            todos = input.todos,
            goals = input.goals,
            focusAreas = input.focusAreas.map { it.name },
        ) + "\n\n" + SCHEMA_INSTRUCTION

        val attempt = runCatching {
            client.complete(PromptLibrary.QUEST_GENERATION_SYSTEM, userPrompt)
        }
        if (attempt.isFailure) {
            val reason = attempt.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "couldn't reach the model"
            return fallback(input, "AI request failed: $reason")
        }

        val proposed = attempt.getOrNull()?.let(::parse)?.mapIndexedNotNull(::toQuest).orEmpty()
        val accepted = validator.validate(proposed, input.existing).accepted
        return when {
            accepted.isNotEmpty() -> Suggestion(accepted, fromAi = true)
            proposed.isEmpty() -> fallback(input, "AI returned an unexpected response.")
            else -> fallback(input, "AI suggestions didn't pass the safety checks.")
        }
    }

    private fun fallback(input: Input, error: String?) = Suggestion(
        quests = FallbackSuggester.suggest(input.todos, input.focusAreas.toSet()),
        fromAi = false,
        error = error,
    )

    /** Extracts the JSON array from a possibly-chatty/markdown-fenced response. */
    internal fun parse(raw: String): List<AiQuestDto> {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val slice = raw.substring(start, end + 1)
        return runCatching {
            json.decodeFromString(ListSerializer(AiQuestDto.serializer()), slice)
        }.getOrDefault(emptyList())
    }

    private fun toQuest(index: Int, dto: AiQuestDto): Quest? {
        val title = dto.title.trim()
        if (title.isBlank()) return null
        val category = enumOrDefault(dto.category, QuestCategory.LIFE_ADMIN)
        val difficulty = enumOrDefault(dto.difficulty, Difficulty.EASY)
        return Quest(
            id = "ai-$index",
            title = title,
            category = category,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = difficulty,
            origin = QuestOrigin.AI_SUGGESTED,
            estimatedMinutes = (dto.estimatedMinutes ?: Quest.defaultMinutes(difficulty)).coerceIn(1, 240),
            isReductionQuest = category == QuestCategory.BAD_HABIT_REDUCTION,
            completionStyle = CompletionStyle.BINARY,
            rationale = dto.rationale?.trim()?.ifBlank { null },
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name.trim().uppercase()) }.getOrDefault(default)

    companion object {
        val SCHEMA_INSTRUCTION: String = buildString {
            appendLine("Respond with ONLY a JSON array (no prose, no markdown fences).")
            appendLine("Each element is an object:")
            appendLine("  {")
            appendLine("    \"title\": string,")
            appendLine("    \"category\": one of ${QuestCategory.entries.joinToString(", ") { it.name }},")
            appendLine("    \"difficulty\": one of ${Difficulty.entries.joinToString(", ") { it.name }},")
            appendLine("    \"estimatedMinutes\": integer,")
            appendLine("    \"rationale\": short string")
            appendLine("  }")
            append("Return at most 6 quests.")
        }
    }
}
