package com.questloop.core.ai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure, platform-free helpers for the "Sign in with ChatGPT" OAuth flow, modelled
 * on OpenAI's Codex CLI (and opencode). Everything here is deterministic and
 * unit-testable; the Android layer ([com.questloop.app.data.OpenAiAuthService])
 * owns the actual sockets, browser launch, HTTP and secure storage.
 *
 * The flow is the standard OAuth 2.0 authorization-code + PKCE handshake against
 * OpenAI's public Codex client. Because that client is registered with a fixed
 * loopback redirect (`http://localhost:1455/auth/callback`), the app captures the
 * callback with a tiny local server rather than a custom URI scheme — the same
 * approach the CLIs use, which works on-device because the browser and the app
 * share the loopback interface.
 */
object OpenAiOAuth {

    /** OpenAI's public Codex CLI client id (a public client — no secret). */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"

    const val AUTHORIZE_ENDPOINT = "https://auth.openai.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://auth.openai.com/oauth/token"

    /** Loopback redirect the Codex client is registered with — must match exactly. */
    const val REDIRECT_PORT = 1455
    const val CALLBACK_PATH = "/auth/callback"
    const val REDIRECT_URI = "http://localhost:$REDIRECT_PORT$CALLBACK_PATH"

    const val SCOPE = "openid profile email offline_access"

    /** Identifies the caller to OpenAI's backend; mirrors the Codex CLI value. */
    const val ORIGINATOR = "codex_cli_rs"

    /** Codex backend that serves a ChatGPT subscription (not api.openai.com). */
    const val API_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"

    /** Beta header the Responses API requires. */
    const val OPENAI_BETA = "responses=experimental"

    /** Custom claim namespace on the id_token that carries the account id. */
    private const val AUTH_CLAIM = "https://api.openai.com/auth"
    private const val ACCOUNT_ID_CLAIM = "chatgpt_account_id"

    private val json = Json { ignoreUnknownKeys = true }

    /** A PKCE pair: the secret [verifier] and its derived [challenge]. */
    data class Pkce(val verifier: String, val challenge: String)

    /**
     * Tokens obtained from the OAuth handshake. [expiresAtEpochSec] is absolute so
     * the refresh decision doesn't depend on when the bundle was created. Stored
     * encrypted on-device; never exported.
     */
    @Serializable
    data class OpenAiTokens(
        val accessToken: String,
        val refreshToken: String,
        val accountId: String = "",
        val expiresAtEpochSec: Long = 0L,
    ) {
        /** True when the access token is at/near expiry (with a safety [skewSec]). */
        fun isExpired(nowEpochSec: Long, skewSec: Long = 60): Boolean =
            nowEpochSec >= expiresAtEpochSec - skewSec
    }

    /** Generates a fresh PKCE pair using a cryptographically secure verifier. */
    fun generatePkce(random: SecureRandom = SecureRandom()): Pkce {
        val verifier = randomUrlSafe(32, random)
        return Pkce(verifier, pkceChallenge(verifier))
    }

    /** S256 challenge = BASE64URL(SHA-256(verifier)), no padding (RFC 7636). */
    fun pkceChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** A random URL-safe token (for the PKCE verifier and the CSRF `state`). */
    fun randomUrlSafe(numBytes: Int, random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(numBytes)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Builds the browser authorization URL. The three `*_organizations` /
     * `codex_*` / `originator` params are OpenAI-specific and required for the
     * simplified Codex login to return organization claims.
     */
    fun authorizeUrl(challenge: String, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to ORIGINATOR,
            "state" to state,
        )
        return AUTHORIZE_ENDPOINT + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
    }

    /** Form body for exchanging an authorization [code] for tokens. */
    fun tokenExchangeForm(code: String, verifier: String): Map<String, String> = linkedMapOf(
        "grant_type" to "authorization_code",
        "client_id" to CLIENT_ID,
        "code" to code,
        "redirect_uri" to REDIRECT_URI,
        "code_verifier" to verifier,
    )

    /** Form body for refreshing an expired access token. */
    fun refreshForm(refreshToken: String): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token",
        "client_id" to refreshClientId(),
        "refresh_token" to refreshToken,
        "scope" to SCOPE,
    )

    private fun refreshClientId() = CLIENT_ID

    /**
     * Parses a token endpoint response into [OpenAiTokens]. [nowEpochSec] is used
     * to turn the relative `expires_in` into an absolute expiry. When the response
     * omits a fresh `refresh_token` (some refresh responses do), [priorRefresh] is
     * kept so the user isn't logged out. Returns null when the body has no usable
     * access token.
     */
    fun parseTokenResponse(
        body: String,
        nowEpochSec: Long,
        priorRefresh: String = "",
    ): OpenAiTokens? {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: priorRefresh
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 0
        val idToken = obj["id_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val account = accountId(idToken).orEmpty()
        return OpenAiTokens(
            accessToken = access,
            refreshToken = refresh,
            accountId = account,
            expiresAtEpochSec = if (expiresIn > 0) nowEpochSec + expiresIn else 0L,
        )
    }

    /** Reads the `chatgpt_account_id` claim from a (JWT) id_token, or null. */
    fun accountId(idToken: String): String? {
        val claims = decodeJwtPayload(idToken) ?: return null
        val auth = claims[AUTH_CLAIM]?.jsonObject ?: return null
        return auth[ACCOUNT_ID_CLAIM]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** Base64url-decodes the middle segment of a JWT into its claims object. */
    fun decodeJwtPayload(jwt: String): JsonObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        val payload = runCatching {
            String(Base64.getUrlDecoder().decode(padBase64(parts[1])), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
    }

    private fun padBase64(s: String): String = when (s.length % 4) {
        2 -> "$s=="
        3 -> "$s="
        else -> s
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
