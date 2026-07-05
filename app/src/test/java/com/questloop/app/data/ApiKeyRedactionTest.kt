package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the privacy guard (Horizon 1, key-safety): the AI API key must never
 * survive into the user-shareable diagnostics log. [redactApiKey] is the scrub
 * QuestRepository.recordAiError applies before any AI error is recorded, so even
 * if an upstream error string ever embeds the key it can't leak.
 */
class ApiKeyRedactionTest {

    @Test
    fun `redacts every occurrence of the key`() {
        val key = "sk-or-v1-supersecret"
        val msg = "OpenRouter failed using $key (sent Bearer $key)"
        val out = redactApiKey(msg, key)
        assertFalse("the key must not survive redaction", out.contains(key))
        assertEquals("OpenRouter failed using *** (sent Bearer ***)", out)
    }

    @Test
    fun `is a no-op for a blank key or text that doesn't contain it`() {
        val msg = "OpenRouter request failed (404) for model \"x\": unknown model"
        assertEquals(msg, redactApiKey(msg, ""))
        assertEquals(msg, redactApiKey(msg, "sk-not-in-this-message"))
    }

    @Test
    fun `redactSecrets scrubs the openrouter key and the openai oauth tokens`() {
        val config = AiConfig(
            provider = AiProvider.OPENAI,
            apiKey = "sk-or-secret",
            openAiTokens = OpenAiOAuth.OpenAiTokens(accessToken = "access-secret", refreshToken = "refresh-secret"),
        )
        val msg = "failed with sk-or-secret / access-secret / refresh-secret embedded"
        val out = redactSecrets(msg, config)
        assertFalse(out.contains("sk-or-secret"))
        assertFalse(out.contains("access-secret"))
        assertFalse(out.contains("refresh-secret"))
    }

    @Test
    fun `redactSecrets scrubs bearer-shaped tokens the config no longer holds`() {
        // A token that rotated mid-call isn't in this config; the Bearer shape still catches it.
        val rotated = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJxbCJ9.sig-material-here"
        val out = redactSecrets("provider echoed our header: Bearer $rotated", AiConfig())
        assertFalse("a rotated token must not survive redaction", out.contains(rotated))
        assertTrue(out.contains("Bearer ***"))
    }

    @Test
    fun `bearer scrub keeps short prose like 'Bearer token' readable`() {
        val msg = "OpenAI request failed (401): invalid Bearer token, sign in again"
        assertEquals(msg, redactSecrets(msg, AiConfig()))
    }
}
