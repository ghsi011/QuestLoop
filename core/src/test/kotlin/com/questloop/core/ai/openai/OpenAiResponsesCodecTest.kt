package com.questloop.core.ai.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiResponsesCodecTest {

    @Test
    fun `request body carries model, instructions and the user message in responses shape`() {
        val body = OpenAiResponsesCodec.buildRequestBody(
            model = "gpt-5",
            systemPrompt = "be terse",
            userPrompt = "make me quests",
        )
        assertTrue(body.contains("\"model\":\"gpt-5\""))
        assertTrue(body.contains("\"instructions\":\"be terse\""))
        assertTrue(body.contains("\"store\":false"))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"type\":\"input_text\""))
        assertTrue(body.contains("\"text\":\"make me quests\""))
        assertTrue(body.contains("\"effort\":\"low\""))
    }

    @Test
    fun `request body escapes quotes and newlines in prompts`() {
        val body = OpenAiResponsesCodec.buildRequestBody("m", "sys", "say \"hi\"\nthen stop")
        // Valid JSON: the quotes and newline are escaped, the literal newline is gone.
        assertTrue(body.contains("\\\"hi\\\""))
        assertTrue(body.contains("\\n"))
        assertEquals(1, body.lineSequence().count())
    }

    @Test
    fun `parseResponse concatenates streamed output_text deltas and ignores reasoning`() {
        val sse = """
            data: {"type":"response.created","response":{}}

            data: {"type":"response.reasoning_summary_text.delta","delta":"thinking..."}

            data: {"type":"response.output_text.delta","delta":"Hello"}

            data: {"type":"response.output_text.delta","delta":", world"}

            data: {"type":"response.completed","response":{"output":[]}}

            data: [DONE]
        """.trimIndent()
        assertEquals("Hello, world", OpenAiResponsesCodec.parseResponse(sse))
    }

    @Test
    fun `parseResponse reads the final message text when there are no deltas`() {
        // A real SSE data payload is a single line; the response object is inlined.
        val output = """[{"type":"reasoning","content":[]},""" +
            """{"type":"message","role":"assistant","content":[{"type":"output_text","text":"[{\"title\":\"Run\"}]"}]}]"""
        val sse = """data: {"type":"response.completed","response":{"output":$output}}"""
        assertEquals("[{\"title\":\"Run\"}]", OpenAiResponsesCodec.parseResponse(sse))
    }

    @Test
    fun `parseResponse handles a plain non-streamed json object`() {
        val jsonBody = """
            {"output":[{"type":"message","content":[{"type":"output_text","text":"plain"}]}]}
        """.trimIndent()
        assertEquals("plain", OpenAiResponsesCodec.parseResponse(jsonBody))
    }

    @Test
    fun `parseResponse handles a top-level output_text convenience field`() {
        assertEquals("quick", OpenAiResponsesCodec.parseResponse("""{"response":{"output_text":"quick"}}"""))
    }

    @Test
    fun `parseResponse returns empty for blank, junk, or unrelated events`() {
        assertEquals("", OpenAiResponsesCodec.parseResponse(""))
        assertEquals("", OpenAiResponsesCodec.parseResponse("garbage that is not sse or json"))
        assertEquals("", OpenAiResponsesCodec.parseResponse("data: not-json\ndata: {\"type\":\"ping\"}"))
    }

    @Test
    fun `parseError surfaces the providers error message`() {
        val body = """{"error":{"message":"insufficient_quota","type":"x"}}"""
        assertEquals("insufficient_quota", OpenAiResponsesCodec.parseError(body))
    }

    @Test
    fun `parseError reads a top-level detail and falls back to the raw body`() {
        assertEquals("nope", OpenAiResponsesCodec.parseError("""{"detail":"nope"}"""))
        assertEquals("plain text failure", OpenAiResponsesCodec.parseError("plain text failure"))
        assertEquals("", OpenAiResponsesCodec.parseError(""))
    }
}
