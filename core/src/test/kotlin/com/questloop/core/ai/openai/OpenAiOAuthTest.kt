package com.questloop.core.ai.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

class OpenAiOAuthTest {

    @Test
    fun `pkce challenge matches the RFC 7636 test vector`() {
        // From RFC 7636 Appendix B.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OpenAiOAuth.pkceChallenge(verifier))
    }

    @Test
    fun `generatePkce produces a verifier whose challenge is its S256 hash`() {
        val pkce = OpenAiOAuth.generatePkce()
        assertTrue(pkce.verifier.length >= 43, "verifier must meet RFC 7636 minimum length")
        assertEquals(OpenAiOAuth.pkceChallenge(pkce.verifier), pkce.challenge)
        // No padding / URL-safe alphabet only.
        assertFalse(pkce.challenge.contains('='))
        assertFalse(pkce.challenge.contains('+'))
        assertFalse(pkce.challenge.contains('/'))
    }

    @Test
    fun `randomUrlSafe is url-safe and effectively unique`() {
        val a = OpenAiOAuth.randomUrlSafe(16)
        val b = OpenAiOAuth.randomUrlSafe(16)
        assertNotEquals(a, b)
        assertFalse(a.contains('='))
    }

    @Test
    fun `authorize url carries every required oauth parameter`() {
        val url = OpenAiOAuth.authorizeUrl(challenge = "chal+/=", state = "st at e")
        assertTrue(url.startsWith(OpenAiOAuth.AUTHORIZE_ENDPOINT + "?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=${OpenAiOAuth.CLIENT_ID}"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("id_token_add_organizations=true"))
        assertTrue(url.contains("codex_cli_simplified_flow=true"))
        assertTrue(url.contains("originator=${OpenAiOAuth.ORIGINATOR}"))
        // Values are URL-encoded (the redirect's :// and the spiked challenge/state).
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
        assertTrue(url.contains("code_challenge=chal%2B%2F%3D"))
        assertTrue(url.contains("state=st+at+e") || url.contains("state=st%20at%20e"))
    }

    @Test
    fun `token exchange form has the pkce verifier and the loopback redirect`() {
        val form = OpenAiOAuth.tokenExchangeForm(code = "the-code", verifier = "the-verifier")
        assertEquals("authorization_code", form["grant_type"])
        assertEquals(OpenAiOAuth.CLIENT_ID, form["client_id"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals(OpenAiOAuth.REDIRECT_URI, form["redirect_uri"])
    }

    @Test
    fun `refresh form uses the refresh grant`() {
        val form = OpenAiOAuth.refreshForm("r3fr3sh")
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("r3fr3sh", form["refresh_token"])
        assertEquals(OpenAiOAuth.CLIENT_ID, form["client_id"])
    }

    @Test
    fun `parseTokenResponse reads tokens, computes absolute expiry, and extracts the account id`() {
        val idToken = jwt(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-123"}}""",
        )
        val body = """
            {"access_token":"at-1","refresh_token":"rt-1","id_token":"$idToken","expires_in":3600}
        """.trimIndent()
        val tokens = OpenAiOAuth.parseTokenResponse(body, nowEpochSec = 1_000)!!
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals("acct-123", tokens.accountId)
        assertEquals(1_000 + 3600, tokens.expiresAtEpochSec)
    }

    @Test
    fun `parseTokenResponse falls back to the prior refresh token when none is returned`() {
        val body = """{"access_token":"at-2","expires_in":60}"""
        val tokens = OpenAiOAuth.parseTokenResponse(body, nowEpochSec = 0, priorRefresh = "keep-me")!!
        assertEquals("keep-me", tokens.refreshToken)
        assertEquals("", tokens.accountId)
    }

    @Test
    fun `parseTokenResponse assumes an hour when expires_in is missing`() {
        val tokens = OpenAiOAuth.parseTokenResponse("""{"access_token":"a","refresh_token":"r"}""", nowEpochSec = 100)!!
        assertEquals(100 + 3600, tokens.expiresAtEpochSec)
    }

    @Test
    fun `parseTokenResponse reads the account id from the access token when the id_token lacks it`() {
        val idToken = jwt("""{"sub":"u1"}""") // no account id
        val accessToken = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"from-access"}}""")
        val body = """{"access_token":"$accessToken","refresh_token":"r","id_token":"$idToken"}"""
        assertEquals("from-access", OpenAiOAuth.parseTokenResponse(body, nowEpochSec = 0)!!.accountId)
    }

    @Test
    fun `accountId reads the top-level claim and the organizations fallback`() {
        assertEquals("top", OpenAiOAuth.accountId(jwt("""{"chatgpt_account_id":"top"}""")))
        assertEquals("org-1", OpenAiOAuth.accountId(jwt("""{"organizations":[{"id":"org-1"},{"id":"org-2"}]}""")))
    }

    @Test
    fun `parseTokenResponse returns null without an access token or on junk`() {
        assertNull(OpenAiOAuth.parseTokenResponse("""{"refresh_token":"x"}""", nowEpochSec = 0))
        assertNull(OpenAiOAuth.parseTokenResponse("not json", nowEpochSec = 0))
    }

    @Test
    fun `accountId is null when the claim is absent or the token is malformed`() {
        assertNull(OpenAiOAuth.accountId(jwt("""{"sub":"u1"}""")))
        assertNull(OpenAiOAuth.accountId("only-one-part"))
        assertNull(OpenAiOAuth.accountId("a.@@@.c"))
    }

    @Test
    fun `tokens report expiry with skew`() {
        val tokens = OpenAiOAuth.OpenAiTokens("a", "r", expiresAtEpochSec = 1_000)
        assertFalse(tokens.isExpired(nowEpochSec = 900))
        assertTrue(tokens.isExpired(nowEpochSec = 950), "within the 60s skew window counts as expired")
        assertTrue(tokens.isExpired(nowEpochSec = 1_500))
    }

    @Test
    fun `unknown expiry is treated as not expired so we don't refresh on every call`() {
        val tokens = OpenAiOAuth.OpenAiTokens("a", "r", expiresAtEpochSec = 0)
        assertFalse(tokens.isExpired(nowEpochSec = 10_000))
    }

    /** Builds a fake (unsigned) JWT whose payload is [payloadJson]. */
    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.sig"
    }

    @Test
    fun `accountId tolerates non-canonical claim shapes instead of throwing`() {
        // The auth claim as a plain string (not the expected object).
        assertNull(OpenAiOAuth.accountId(jwt("""{"https://api.openai.com/auth":"oops"}""")))
        // The top-level account id as an object; organizations entries malformed.
        assertNull(OpenAiOAuth.accountId(jwt("""{"chatgpt_account_id":{"v":1}}""")))
        assertNull(OpenAiOAuth.accountId(jwt("""{"organizations":[{"id":{"v":1}}]}""")))
        assertNull(OpenAiOAuth.accountId(jwt("""{"organizations":"none"}""")))
    }

    @Test
    fun `parseTokenResponse returns null on a non-string access token instead of throwing`() {
        assertNull(OpenAiOAuth.parseTokenResponse("""{"access_token":{"v":1}}""", nowEpochSec = 0))
    }

    @Test
    fun `parseTokenResponse tolerates malformed sibling fields`() {
        // access_token is good; refresh/expiry/id_token have drifted shapes → keep
        // the token, fall back to prior refresh + default expiry.
        val tokens = OpenAiOAuth.parseTokenResponse(
            """{"access_token":"at","refresh_token":{"v":1},"expires_in":"soon","id_token":[1]}""",
            nowEpochSec = 100,
            priorRefresh = "prior",
        )
        assertEquals("at", tokens?.accessToken)
        assertEquals("prior", tokens?.refreshToken)
        assertEquals(100 + 3600L, tokens?.expiresAtEpochSec)
    }
}
