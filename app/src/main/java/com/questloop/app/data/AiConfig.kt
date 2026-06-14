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
        const val DEFAULT_MODEL = "google/gemma-2-9b-it:free"

        /** A few free OpenRouter models offered as quick presets. */
        val FREE_MODEL_PRESETS = listOf(
            "google/gemma-2-9b-it:free",
            "meta-llama/llama-3.1-8b-instruct:free",
            "mistralai/mistral-7b-instruct:free",
        )
    }
}
