package com.questloop.app.data

/**
 * AI provider configuration. Stored on-device, separate from [UserProfile] so
 * the API key is never included in data export. Only OpenRouter is supported in
 * the MVP; the model is user-editable (free OpenRouter models are offered as
 * presets).
 */
data class AiConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
) {
    /** True when AI suggestions can actually be requested. */
    val usable: Boolean get() = enabled && apiKey.isNotBlank()

    companion object {
        /**
         * OpenRouter's Free Models Router auto-selects a working free model (and
         * filters for ones that support structured output), so it can't go stale
         * the way a single pinned model can. That makes it the safest default.
         */
        const val DEFAULT_MODEL = "openrouter/free"

        /**
         * Quick-pick free models (current as of June 2026). The first auto-selects;
         * the rest are strong specific models. OpenRouter's free line-up changes
         * over time, so if one returns "model not found", pick another or paste any
         * id from openrouter.ai/models.
         */
        val FREE_MODEL_PRESETS = listOf(
            "openrouter/free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "deepseek/deepseek-chat-v3-0324:free",
            "meta-llama/llama-4-maverick:free",
            "deepseek/deepseek-r1:free",
        )
    }
}
