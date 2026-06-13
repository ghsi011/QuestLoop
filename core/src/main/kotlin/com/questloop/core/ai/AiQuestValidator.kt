package com.questloop.core.ai

import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory

/**
 * Guardrails for AI-proposed quests (SPEC section 11: AI is product-critical).
 *
 * Validates and sanitizes model output before it ever reaches the user:
 * - rejects shame/guilt language and financial/medical advice,
 * - clamps unrealistic time estimates and excessive difficulty inflation,
 * - de-duplicates against existing quests,
 * - drops empty/garbage entries.
 *
 * When validation removes too much, the caller falls back to
 * [FallbackSuggester] so the user is never left with nothing or with unsafe text.
 */
class AiQuestValidator(private val config: Config = Config()) {

    data class Config(
        val maxMinutes: Int = 240,
        val maxQuests: Int = 8,
    )

    data class Result(
        val accepted: List<Quest>,
        val rejected: List<Rejection>,
    )

    data class Rejection(val title: String, val reason: String)

    // Word-boundary patterns avoid false positives (e.g. "crypto" must not match
    // "cryptography", "loan" must not match "download").
    private val bannedPatterns: List<Regex> = listOf(
        // shame / pressure
        "lazy", "pathetic", "worthless", "failure as a", "you should be ashamed",
        "no excuse", "stop being", "disgusting",
        // financial advice
        "invest in", "buy stock", "crypto(currenc\\w*|s)?", "guaranteed return",
        "loan", "leverage",
        // medical advice
        "diagnos\\w*", "prescri\\w*", "medication dose", "you have a disorder",
    ).map { Regex("\\b$it\\b", RegexOption.IGNORE_CASE) }

    fun validate(proposed: List<Quest>, existing: List<Quest> = emptyList()): Result {
        val accepted = mutableListOf<Quest>()
        val rejected = mutableListOf<Rejection>()
        val existingTitles = existing.map { it.title.normalized() }.toMutableSet()

        for (raw in proposed) {
            if (accepted.size >= config.maxQuests) {
                rejected += Rejection(raw.title, "Exceeded max quests for one batch.")
                continue
            }
            val title = raw.title.trim()
            if (title.isBlank()) {
                rejected += Rejection(raw.title, "Empty title.")
                continue
            }
            val haystack = "$title ${raw.rationale.orEmpty()}"
            val unsafe = bannedPatterns.firstOrNull { it.containsMatchIn(haystack) }
            if (unsafe != null) {
                rejected += Rejection(title, "Contains disallowed content.")
                continue
            }
            val norm = title.normalized()
            if (norm in existingTitles) {
                rejected += Rejection(title, "Duplicate of an existing quest.")
                continue
            }

            // Clamp unrealistic estimates rather than discarding useful quests.
            val safeMinutes = raw.estimatedMinutes.coerceIn(1, config.maxMinutes)
            // Reductions must be tagged correctly so the reward engine treats
            // relapse with honesty, not punishment.
            val safeReduction = raw.category == QuestCategory.BAD_HABIT_REDUCTION
            val sanitized = raw.copy(
                title = title,
                estimatedMinutes = safeMinutes,
                isReductionQuest = safeReduction,
                rationale = raw.rationale?.trim()?.ifBlank { null },
            )
            accepted += sanitized
            existingTitles += norm
        }
        return Result(accepted, rejected)
    }

    private fun String.normalized() = trim().lowercase().replace(Regex("\\s+"), " ")
}

/**
 * Deterministic, always-safe fallback when AI output is unusable or the model is
 * unavailable (SPEC 11: fallback behavior). Produces gentle, generic quests from
 * the user's stated focus areas / todos.
 */
object FallbackSuggester {

    fun suggest(todos: List<String>, focus: Set<QuestCategory>, max: Int = 3): List<Quest> {
        val out = mutableListOf<Quest>()
        todos.take(max).forEachIndexed { i, todo ->
            out += Quest(
                id = "fallback-todo-$i",
                title = todo.trim().ifBlank { "Small step forward" },
                category = QuestCategory.LIFE_ADMIN,
                frequency = com.questloop.core.model.QuestFrequency.ONE_OFF,
                difficulty = Difficulty.EASY,
                rationale = "From your todo list.",
            )
        }
        if (out.isEmpty()) {
            val cat = focus.firstOrNull() ?: QuestCategory.PERSONAL_GROWTH
            out += Quest(
                id = "fallback-default",
                title = "Take one small step in ${cat.name.lowercase().replace('_', ' ')}",
                category = cat,
                frequency = com.questloop.core.model.QuestFrequency.DAILY,
                difficulty = Difficulty.EASY,
                rationale = "A safe, easy starting point.",
            )
        }
        return out
    }
}
