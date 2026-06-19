package com.questloop.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
