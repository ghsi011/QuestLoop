package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the token-endpoint side of the OAuth service (refresh) against a local
 * [MockWebServer]. The interactive [OpenAiAuthService.signIn] loopback flow needs
 * a browser + socket and is left to manual/instrumented verification.
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
}
