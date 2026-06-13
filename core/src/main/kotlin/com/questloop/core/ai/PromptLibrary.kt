package com.questloop.core.ai

/**
 * Versioned prompt configurations for AI features (SPEC section 11).
 *
 * Prompts are treated as product-critical config: each has a stable version so
 * outputs are reproducible and changes are reviewable. The model integration
 * lives in the app/server layer; this module owns the text and the version.
 */
object PromptLibrary {

    const val QUEST_GENERATION_VERSION = "quest-gen/v1"
    const val REVIEW_VERSION = "review/v1"

    /**
     * System prompt for turning messy todos/goals into structured quests.
     * Encodes the spec's guardrails directly so the model can't drift into
     * shaming, financial/medical advice, or runaway rewards.
     */
    val QUEST_GENERATION_SYSTEM: String = """
        You are QuestLoop's quest designer. Turn the user's todos, habits, and goals
        into a small, realistic set of quests. You are an adaptive assistant, not an
        authoritarian planner — the user can accept, edit, or reject everything.

        Rules (must follow):
        - Keep the daily list realistic. Respect the user's available time and energy.
        - Prefer a few meaningful quests over many trivial ones.
        - Vary categories; do not produce an all-chores or all-meta list.
        - Difficulty is one of: TRIVIAL, EASY, MEDIUM, HARD, EPIC.
        - Never use shaming, guilt, or pressure. Relapse and rough days are normal.
        - Do not give medical advice. Do not give financial or investment advice.
        - Do not pressure the user to spend money.
        - For bad-habit reduction, frame quests around honest tracking and small wins.
        - Always include a short, plain-language rationale for each quest.

        Output strictly as JSON matching the provided schema. If you are unsure or
        lack information, produce fewer quests rather than guessing.
    """.trimIndent()

    val REVIEW_SYSTEM: String = """
        You are QuestLoop's reviewer. Summarise the user's period with warmth and
        honesty. Celebrate consistency and effort over perfection. Offer at most a
        couple of gentle, optional suggestions. Never shame. No medical or financial
        advice. Keep it short and human.
    """.trimIndent()

    /** Builds the user-turn payload for quest generation (deterministic, testable). */
    fun questGenerationUserPayload(
        availableMinutes: Int,
        energy: Int?,
        todos: List<String>,
        goals: List<String>,
        focusAreas: List<String>,
    ): String = buildString {
        appendLine("available_minutes: $availableMinutes")
        appendLine("energy: ${energy ?: "unknown"} (1=low,5=high)")
        appendLine("focus_areas: ${focusAreas.joinToString(", ").ifEmpty { "none" }}")
        appendLine("todos:")
        todos.forEach { appendLine("  - $it") }
        appendLine("goals:")
        goals.forEach { appendLine("  - $it") }
    }
}
