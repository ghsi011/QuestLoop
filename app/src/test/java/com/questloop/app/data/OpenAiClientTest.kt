package com.questloop.app.data

import com.questloop.core.ai.AiQuestService
import com.questloop.core.ai.openai.OpenAiOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Integration tests for the OpenAI Codex client against a local [MockWebServer]
 * speaking the streamed Responses API. Exercises the real OkHttp stack, the auth
 * headers, the SSE parsing, and the 401 refresh-and-retry — no network or login.
 */
class OpenAiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun tokens(suffix: String = "1") =
        OpenAiOAuth.OpenAiTokens(accessToken = "at-$suffix", refreshToken = "rt", accountId = "acct-9")

    private fun sse(vararg lines: String) = lines.joinToString("\n\n")

    @Test
    fun `successful completion parses deltas and sends auth + account headers`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                sse(
                    """data: {"type":"response.output_text.delta","delta":"hello "}""",
                    """data: {"type":"response.output_text.delta","delta":"world"}""",
                    """data: {"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
        )
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { tokens() }

        assertEquals("hello world", client.complete("system", "user"))

        val recorded = server.takeRequest()
        assertEquals("Bearer at-1", recorded.getHeader("Authorization"))
        assertEquals("acct-9", recorded.getHeader("chatgpt-account-id"))
        assertEquals("responses=experimental", recorded.getHeader("OpenAI-Beta"))
        assertEquals(OpenAiOAuth.ORIGINATOR, recorded.getHeader("originator"))
        assertEquals(OpenAiOAuth.USER_AGENT, recorded.getHeader("User-Agent"))
        val body = recorded.body.readUtf8()
        assertTrue("request carries the model", body.contains("\"model\":\"gpt-5\""))
        assertTrue("request carries the prompt", body.contains("user"))
    }

    @Test
    fun `a 401 triggers one forced refresh and retries`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(sse("""data: {"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}}""")),
        )
        val forces = mutableListOf<Boolean>()
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { force ->
            forces.add(force)
            tokens(if (force) "fresh" else "stale")
        }

        assertEquals("ok", client.complete("s", "u"))
        assertEquals("the client refreshes exactly once on a 401", listOf(false, true), forces)
        server.takeRequest()
        assertEquals("Bearer at-fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `a persistent 401 surfaces a sign-in error`() = runTest {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("nope")) }
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { tokens() }
        val error = runCatching { client.complete("s", "u") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("401"))
    }

    @Test
    fun `a non-401 error surfaces the provider message and status`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody("""{"error":{"message":"rate limited"}}"""),
        )
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { tokens() }
        val error = runCatching { client.complete("s", "u") }.exceptionOrNull()
        assertTrue(error is IOException)
        val msg = error?.message.orEmpty()
        assertTrue(msg.contains("429"))
        assertTrue(msg.contains("rate limited"))
    }

    @Test
    fun `cancelling the caller aborts the in-flight call instead of blocking to the read timeout`() = runBlocking {
        // The server accepts the request and then never responds. runBlocking (real
        // time, not runTest's virtual time) because this races a cancel against real IO.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { tokens() }

        val job = launch(Dispatchers.IO) { runCatching { client.complete("s", "u") } }
        server.takeRequest() // request is on the wire → the client is now awaiting the response
        job.cancel()
        // Without cancellation propagating into OkHttp, join would block for the
        // full 120s read timeout; with it, the call aborts near-instantly.
        assertNotNull(
            "cancelling the coroutine must cancel the OkHttp call",
            withTimeoutOrNull(10_000) { job.join() },
        )
    }

    @Test
    fun `AiQuestService turns a mock SSE success into ai quests end to end`() = runTest {
        val arrayJson = """[{\"title\":\"Pay rent\",\"category\":\"LIFE_ADMIN\",\"difficulty\":\"EASY\"}]"""
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(sse("""data: {"type":"response.output_text.delta","delta":"$arrayJson"}""")),
        )
        val client = OpenAiClient("gpt-5", endpoint = server.url("/codex/responses").toString()) { tokens() }

        val result = AiQuestService(client).suggest(AiQuestService.Input(todos = listOf("rent")))
        assertTrue(result.fromAi)
        assertEquals("Pay rent", result.quests.single().title)
        assertEquals(null, result.error)
    }
}
