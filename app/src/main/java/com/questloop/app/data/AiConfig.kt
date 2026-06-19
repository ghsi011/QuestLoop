package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth

/** Which AI backend the user has chosen. */
enum class AiProvider {
    /** Bring-your-own OpenRouter API key (the original MVP provider). */
    OPENROUTER,

    /** "Sign in with ChatGPT" — OAuth against OpenAI's Codex backend, no key. */
    OPENAI,
}

/**
 * AI provider configuration. Stored on-device, separate from [UserProfile] so the
 * credentials are never included in data export. Two providers are supported: an
 * OpenRouter API key, or OpenAI via the ChatGPT OAuth login (the Codex flow).
 */
data class AiConfig(
    val enabled: Boolean = false,
    /** The selected backend. Defaults to OpenRouter for backwards compatibility. */
    val provider: AiProvider = AiProvider.OPENROUTER,
    /** OpenRouter API key (only used when [provider] is [AiProvider.OPENROUTER]). */
    val apiKey: String = "",
    /** OpenRouter model (free presets offered). */
    val model: String = DEFAULT_MODEL,
    /** OAuth tokens for the OpenAI ChatGPT login; null until the user signs in. */
    val openAiTokens: OpenAiOAuth.OpenAiTokens? = null,
    /** OpenAI model to request (only used when [provider] is [AiProvider.OPENAI]). */
    val openAiModel: String = OPENAI_DEFAULT_MODEL,
    /**
     * When true (default), AI summaries are run through the slop filter that drops
     * flattery, filler, and robotic phrasing. Turn off to show raw model output.
     */
    val filterWording: Boolean = true,
) {
    /** True when AI suggestions can actually be requested for the chosen provider. */
    val usable: Boolean
        get() = enabled && when (provider) {
            AiProvider.OPENROUTER -> apiKey.isNotBlank()
            AiProvider.OPENAI -> openAiConnected
        }

    /** True when an OpenAI account is linked, independent of the on/off switch. */
    val openAiConnected: Boolean
        get() = openAiTokens?.refreshToken?.isNotBlank() == true

    /** A short label of the OpenAI sign-in, e.g. for the settings screen. */
    val openAiAccountLabel: String?
        get() = openAiTokens?.accountId?.takeIf { it.isNotBlank() }

    companion object {
        /**
         * OpenRouter's Free Models Router auto-selects a working free model (and
         * filters for ones that support structured output), so it can't go stale
         * the way a single pinned model can. That makes it the safest default.
         */
        const val DEFAULT_MODEL = "openrouter/free"

        /** Default OpenAI model for the Codex/Responses backend. */
        const val OPENAI_DEFAULT_MODEL = "gpt-5"

        /**
         * Quick-pick free models. The first auto-selects a working free model; the
         * rest are specific picks. OpenRouter's free line-up changes over time, so
         * if one returns "model not found", pick another or paste any id from
         * openrouter.ai/models.
         */
        val FREE_MODEL_PRESETS = listOf(
            "openrouter/free",
            "nex-agi/nex-n2-pro:free",
            "nvidia/nemotron-3-super-120b-a12b:free",
            "qwen/qwen3-next-80b-a3b-instruct:free",
        )

        /** Models served by the ChatGPT subscription Codex backend. */
        val OPENAI_MODEL_PRESETS = listOf(
            "gpt-5",
            "gpt-5-codex",
        )
    }
}
