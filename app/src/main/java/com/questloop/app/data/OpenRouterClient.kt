package com.questloop.app.data

import com.questloop.core.ai.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [LlmClient] backed by OpenRouter's OpenAI-compatible chat completions API.
 * The API key and model are supplied per call from the user's [AiConfig]; the
 * key is never logged or persisted here.
 */
class OpenRouterClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = ENDPOINT,
    private val http: OkHttpClient = sharedClient,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", systemPrompt),
                    ChatMessage("user", userPrompt),
                ),
            ),
        )
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            // Optional attribution headers recommended by OpenRouter.
            .addHeader("HTTP-Referer", "https://github.com/ghsi011/QuestLoop")
            .addHeader("X-Title", "QuestLoop")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Surface the model/provider's own reason (e.g. an unknown model on a
                // 404) instead of a bare status code, so the user can act on it.
                val detail = providerError(body)
                throw IOException(
                    "OpenRouter request failed (${response.code})" +
                        if (detail.isNotBlank()) " for model \"$model\": $detail" else " for model \"$model\".",
                )
            }
            json.decodeFromString(ChatResponse.serializer(), body)
                .choices.firstOrNull()?.message?.content.orEmpty()
        }
    }

    /** Extracts OpenRouter's `{ "error": { "message": ... } }` text, or a trimmed body. */
    private fun providerError(body: String): String {
        if (body.isBlank()) return ""
        val parsed = runCatching {
            json.decodeFromString(ErrorEnvelope.serializer(), body).error?.message
        }.getOrNull()
        return (parsed ?: body).trim().take(300)
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.4,
    )

    // role defaults so response parsing tolerates messages that omit it.
    @Serializable
    private data class ChatMessage(val role: String = "", val content: String = "")

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ChatMessage? = null)

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody? = null)

    @Serializable
    private data class ErrorBody(val message: String? = null, val code: Int? = null)

    companion object {
        private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
