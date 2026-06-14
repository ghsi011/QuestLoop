package com.questloop.core.ai

/**
 * Minimal boundary for a chat-style LLM call. Implemented in the app layer
 * (e.g. OpenRouter over HTTP); kept as an interface here so [AiQuestService] —
 * the prompt building, parsing, and guardrails — stays pure and unit-testable
 * with a fake, and so `:core` takes no networking/platform dependency.
 */
interface LlmClient {
    /** Returns the model's raw text completion, or throws on transport failure. */
    suspend fun complete(systemPrompt: String, userPrompt: String): String
}
