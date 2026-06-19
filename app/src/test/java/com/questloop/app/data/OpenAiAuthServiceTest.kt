package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Tests both sides of the OAuth service against a local [MockWebServer] for the
 * token endpoint: token refresh, and the full loopback sign-in handshake driven
 * over a real socket (the part that, in production, the system browser performs).
 */
class OpenAiAuthServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun service() = OpenAiAuthService(
        tokenEndpoint = server.url("/oauth/token").toString(),
        nowEpochSec = { 1_000 },
    )

    @Test
    fun `refresh swaps the access token, keeps the prior account id, and sends the refresh grant`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"new-at","refresh_token":"new-rt","expires_in":3600}"""),
        )
        val old = OpenAiOAuth.OpenAiTokens("old-at", "old-rt", accountId = "acct-7", expiresAtEpochSec = 0)

        val refreshed = service().refresh(old).getOrThrow()
        assertEquals("new-at", refreshed.accessToken)
        assertEquals("new-rt", refreshed.refreshToken)
        // No id_token in the response → keep the account id we already had.
        assertEquals("acct-7", refreshed.accountId)
        assertEquals(1_000 + 3600, refreshed.expiresAtEpochSec)

        val form = server.takeRequest().body.readUtf8()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=old-rt"))
    }

    @Test
    fun `a failed refresh is returned as a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val result = service().refresh(OpenAiOAuth.OpenAiTokens("a", "r"))
        assertTrue(result.isFailure)
    }

    /**
     * Hits the loopback callback the way the browser would, on a separate thread so
     * the server's blocking accept() isn't deadlocked by the same thread. [extraParams]
     * is appended after a parsed-and-echoed `state` (when [echoState] is true).
     */
    private fun driveCallback(authorizeUrl: String, query: (state: String) -> String) {
        thread {
            val state = Regex("state=([^&]+)").find(authorizeUrl)?.groupValues?.get(1)?.let {
                URLDecoder.decode(it, "UTF-8")
            }.orEmpty()
            runCatching {
                Socket("127.0.0.1", OpenAiOAuth.REDIRECT_PORT).use { socket ->
                    val request = "GET /auth/callback?${query(state)} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(request.toByteArray()); flush() }
                    socket.getInputStream().readBytes() // drain the success page so the server can close
                }
            }
        }
    }

    @Test
    fun `a full loopback sign-in exchanges the code for tokens`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"at","refresh_token":"rt","expires_in":3600}""",
            ),
        )
        val tokens = service().signIn(timeoutMs = 5_000) { url ->
            driveCallback(url) { state -> "code=auth-code&state=$state" }
        }.getOrThrow()

        assertEquals("at", tokens.accessToken)
        assertEquals("rt", tokens.refreshToken)
        val exchange = server.takeRequest().body.readUtf8()
        assertTrue("exchanges the authorization code", exchange.contains("grant_type=authorization_code"))
        assertTrue(exchange.contains("code=auth-code"))
    }

    @Test
    fun `sign-in fails when the provider returns an error`() = runTest {
        val result = service().signIn(timeoutMs = 5_000) { url ->
            driveCallback(url) { state -> "error=access_denied&state=$state" }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `sign-in rejects a mismatched state (CSRF guard)`() = runTest {
        val result = service().signIn(timeoutMs = 5_000) { _ ->
            driveCallback("state=ignored") { "code=x&state=not-the-real-state" }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `sign-in times out when no callback arrives`() = runTest {
        var opened = false
        val result = service().signIn(timeoutMs = 300) { opened = true }
        assertTrue(opened)
        assertFalse(result.isSuccess)
    }
}
