package com.questloop.app.data

import com.questloop.core.ai.AiQuestService
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
 * Integration tests for the OpenRouter client against a local [MockWebServer]
 * that speaks the same chat-completions API as the provider. This exercises the
 * real OkHttp stack, JSON (de)serialization, auth header, and error handling
 * without any external network or API key — runnable in CI.
 */
class OpenRouterClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(model: String = "test-model") = OpenRouterClient(
        apiKey = "sk-test",
        model = model,
        endpoint = server.url("/v1/chat/completions").toString(),
    )

    @Test
    fun `successful completion returns content and sends auth plus model`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"hello world"}}]}"""),
        )

        val out = client().complete("system prompt", "user prompt")
        assertEquals("hello world", out)

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue("request carries the model", body.contains("\"model\":\"test-model\""))
        assertTrue("request carries the prompts", body.contains("user prompt"))
    }

    @Test
    fun `a 404 surfaces the provider error message and the model`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"message":"No endpoints found for missing-model","code":404}}"""),
        )

        val error = runCatching { client("missing-model").complete("s", "u") }.exceptionOrNull()
        assertTrue(error is IOException)
        val msg = error?.message.orEmpty()
        assertTrue("mentions status", msg.contains("404"))
        assertTrue("mentions provider reason", msg.contains("No endpoints found for missing-model"))
        assertTrue("mentions the model", msg.contains("missing-model"))
    }

    @Test
    fun `a 5xx surfaces an error`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(503).setBody("""{"error":{"message":"upstream is down"}}"""),
        )
        val error = runCatching { client().complete("s", "u") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error!!.message.orEmpty().contains("503"))
    }

    @Test
    fun `a 200 with a non-json body surfaces an unreadable-response error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>maintenance</html>"))
        val error = runCatching { client().complete("s", "u") }.exceptionOrNull()
        assertTrue("non-json 200 must not escape as SerializationException", error is IOException)
    }

    @Test
    fun `a 200 with no choices returns empty content`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[]}"""))
        assertEquals("", client().complete("s", "u"))
    }

    @Test
    fun `cancelling the caller aborts the in-flight call instead of blocking to the read timeout`() = runBlocking {
        // The server accepts the request and then never responds. runBlocking (real
        // time, not runTest's virtual time) because this races a cancel against real IO.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val job = launch(Dispatchers.IO) { runCatching { client().complete("s", "u") } }
        server.takeRequest() // request is on the wire → the client is now awaiting the response
        job.cancel()
        // Without cancellation propagating into OkHttp, join would block for the
        // full 60s read timeout; with it, the call aborts near-instantly.
        assertNotNull(
            "cancelling the coroutine must cancel the OkHttp call",
            withTimeoutOrNull(10_000) { job.join() },
        )
    }

    @Test
    fun `AiQuestService turns a mock success into ai quests end to end`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"[{\"title\":\"Pay rent\",\"category\":\"LIFE_ADMIN\",\"difficulty\":\"EASY\"}]"}}]}""",
            ),
        )

        val result = AiQuestService(client()).suggest(AiQuestService.Input(todos = listOf("rent")))
        assertTrue(result.fromAi)
        assertEquals("Pay rent", result.quests.single().title)
        assertEquals(null, result.error)
    }
}
