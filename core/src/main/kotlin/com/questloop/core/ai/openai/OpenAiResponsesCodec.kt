package com.questloop.core.ai.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure encoder/decoder for OpenAI's Responses API as served by the ChatGPT
 * subscription Codex backend. Building the request body and parsing the streamed
 * (SSE) response are the brittle, reverse-engineered parts, so they live here in
 * `:core` where they're deterministic and unit-tested; [com.questloop.app.data.OpenAiClient]
 * only does the HTTP.
 */
object OpenAiResponsesCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Builds the Responses request body. The Codex backend requires `store=false`
     * and `stream=true`; the system prompt maps to `instructions` and the user
     * prompt to a single `input` message of `input_text` (the Responses shape).
     */
    fun buildRequestBody(model: String, systemPrompt: String, userPrompt: String): String {
        val obj = buildJsonObject {
            put("model", model)
            put("instructions", systemPrompt)
            putJsonArray("input") {
                addJsonObject {
                    put("type", "message")
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_text")
                            put("text", userPrompt)
                        }
                    }
                }
            }
            put("store", false)
            put("stream", true)
            putJsonObject("reasoning") { put("effort", "low") }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * Extracts the assistant's text from a Responses reply. Handles three shapes,
     * in order of preference:
     *  1. an SSE stream — concatenates `response.output_text.delta` chunks;
     *  2. a terminal `response.completed`/`response.done` event — reads the final
     *     message text from its `response.output`;
     *  3. a plain (non-streamed) JSON object — reads `output` directly.
     * Returns "" when nothing usable is present (callers treat that as a miss).
     */
    fun parseResponse(raw: String): String {
        if (raw.isBlank()) return ""
        val deltas = StringBuilder()
        var finalFromEvent: String? = null
        var sawCompleted = false
        var sawFailure = false

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val event = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
            when (event["type"].stringOrNull()) {
                "response.output_text.delta" ->
                    event["delta"].stringOrNull()?.let { deltas.append(it) }
                "response.completed", "response.done" -> {
                    sawCompleted = true
                    finalFromEvent = textOf(event["response"]?.asObject())
                }
                "response.failed", "error", "response.error" -> sawFailure = true
                // A bare error envelope (no "type") also signals failure.
                else -> if (event["error"] != null) sawFailure = true
            }
        }

        // A failure event before any successful completion means the generation
        // didn't finish — don't pass partial deltas off as a complete answer.
        if (sawFailure && !sawCompleted) return ""
        if (deltas.isNotEmpty()) return deltas.toString()
        if (!finalFromEvent.isNullOrBlank()) return finalFromEvent

        // Non-SSE fallback: the whole body is a single JSON object.
        val whole = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (whole != null) {
            val text = textOf(whole["response"]?.asObject() ?: whole)
            if (text.isNotBlank()) return text
        }
        return finalFromEvent.orEmpty()
    }

    /**
     * Best-effort provider error message from an error body — either the Responses
     * `{ "error": { "message": ... } }` envelope, a top-level `detail`, or the
     * trimmed raw body. Used to surface a useful reason instead of a bare status.
     */
    fun parseError(body: String): String {
        if (body.isBlank()) return ""
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = obj?.get("error")?.asObject()?.get("message").stringOrNull()
            ?: obj?.get("detail").stringOrNull()
        return (message ?: body).trim().take(300)
    }

    /** Concatenates every `output_text` across the `message` items of a response. */
    private fun textOf(response: JsonObject?): String {
        response ?: return ""
        val output = response["output"]?.asArray() ?: return topLevelText(response)
        val sb = StringBuilder()
        for (item in output) {
            val itemObj = item.asObject() ?: continue
            if (itemObj["type"].stringOrNull() != "message") continue
            val content = itemObj["content"]?.asArray() ?: continue
            for (part in content) {
                val partObj = part.asObject() ?: continue
                if (partObj["type"].stringOrNull() == "output_text") {
                    partObj["text"].stringOrNull()?.let { sb.append(it) }
                }
            }
        }
        return sb.toString().ifBlank { topLevelText(response) }
    }

    /** Some responses expose a convenience `output_text` string at the top level. */
    private fun topLevelText(response: JsonObject): String =
        response["output_text"].stringOrNull().orEmpty()
}
