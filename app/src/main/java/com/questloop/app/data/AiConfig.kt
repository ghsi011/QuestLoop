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
        const val DEFAULT_MODEL = "meta-llama/llama-3.3-70b-instruct:free"

        /**
         * A few free OpenRouter models offered as quick presets. OpenRouter's free
         * line-up changes over time; if one returns "model not found", pick another
         * or paste any id from openrouter.ai/models.
         */
        val FREE_MODEL_PRESETS = listOf(
            "meta-llama/llama-3.3-70b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
            "qwen/qwen-2.5-7b-instruct:free",
        )
    }
}
