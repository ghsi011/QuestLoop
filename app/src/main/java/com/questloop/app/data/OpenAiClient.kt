package com.questloop.app.data

import com.questloop.core.ai.LlmClient
import com.questloop.core.ai.openai.OpenAiOAuth
import com.questloop.core.ai.openai.OpenAiResponsesCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * [LlmClient] backed by OpenAI's Codex Responses API, authenticated with the
 * ChatGPT OAuth tokens (no API key). Tokens are supplied lazily by [freshTokens]
 * so they can be refreshed just before the call; a 401 triggers one forced
 * refresh + retry in case the access token was revoked early.
 *
 * The request/response shaping (a brittle, reverse-engineered surface) lives in
 * core's [OpenAiResponsesCodec]; this class only does the HTTP and auth headers.
 */
class OpenAiClient(
    private val model: String,
    private val endpoint: String = OpenAiOAuth.API_RESPONSES_URL,
    private val http: OkHttpClient = sharedClient,
    // Kept last so callers can pass it as a trailing lambda.
    private val freshTokens: suspend (forceRefresh: Boolean) -> OpenAiOAuth.OpenAiTokens,
) : LlmClient {

    override suspend fun complete(systemPrompt: String, userPrompt: String): String = withContext(Dispatchers.IO) {
        val payload = OpenAiResponsesCodec.buildRequestBody(model, systemPrompt, userPrompt)
        // First attempt with the current (lazily refreshed) token; on a 401, force a
        // refresh once and retry, since the access token may have been revoked early.
        when (val first = call(payload, freshTokens(false))) {
            is Outcome.Ok -> first.text
            is Outcome.Unauthorized -> when (val retry = call(payload, freshTokens(true))) {
                is Outcome.Ok -> retry.text
                is Outcome.Unauthorized -> throw IOException("OpenAI rejected the sign-in (401). Please sign in again.")
                is Outcome.Failed -> throw retry.error
            }
            is Outcome.Failed -> throw first.error
        }
    }

    private fun call(payload: String, tokens: OpenAiOAuth.OpenAiTokens): Outcome {
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${tokens.accessToken}")
            .addHeader("OpenAI-Beta", OpenAiOAuth.OPENAI_BETA)
            .addHeader("originator", OpenAiOAuth.ORIGINATOR)
            .addHeader("session_id", UUID.randomUUID().toString())
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
        if (tokens.accountId.isNotBlank()) builder.addHeader("chatgpt-account-id", tokens.accountId)

        return try {
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 -> Outcome.Unauthorized
                    !response.isSuccessful -> {
                        val detail = OpenAiResponsesCodec.parseError(body)
                        Outcome.Failed(
                            IOException(
                                "OpenAI request failed (${response.code})" +
                                    if (detail.isNotBlank()) " for model \"$model\": $detail" else " for model \"$model\".",
                            ),
                        )
                    }
                    else -> Outcome.Ok(OpenAiResponsesCodec.parseResponse(body))
                }
            }
        } catch (e: IOException) {
            Outcome.Failed(e)
        }
    }

    private sealed interface Outcome {
        data class Ok(val text: String) : Outcome
        object Unauthorized : Outcome
        data class Failed(val error: IOException) : Outcome
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // Reasoning models can be slow; give the streamed response room.
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }
}
