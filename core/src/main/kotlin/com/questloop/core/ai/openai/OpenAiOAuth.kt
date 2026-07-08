package com.questloop.core.ai.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

    /**
     * Identifies the calling app to OpenAI's backend, sent both as an authorize
     * param and as a request header. Each client uses its own id (the Codex CLI
     * sends `codex_cli_rs`, opencode `opencode`, Zed `zed`); the backend accepts
     * arbitrary values, so we send our own. If a future backend ever requires a
     * known originator, this is the single knob to change.
     */
    const val ORIGINATOR = "questloop"

    /** Sent as the User-Agent on every OpenAI call (opencode/Codex send their own). */
    const val USER_AGENT = "QuestLoop"

    /** Codex backend that serves a ChatGPT subscription (not api.openai.com). */
    const val API_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses"

    /** Beta header the Responses API requires. */
    const val OPENAI_BETA = "responses=experimental"

    /** Assumed access-token lifetime when the token response omits expires_in. */
    private const val DEFAULT_EXPIRES_IN_SEC = 3600L

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
        /**
         * True when the access token is at/near expiry (with a safety [skewSec]). An
         * unknown expiry ([expiresAtEpochSec] <= 0) is treated as NOT expired so we
         * don't refresh on every call (which would burn the rotating refresh token);
         * a genuinely dead token is then caught by the 401 retry instead.
         */
        fun isExpired(nowEpochSec: Long, skewSec: Long = 60): Boolean =
            expiresAtEpochSec > 0 && nowEpochSec >= expiresAtEpochSec - skewSec
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

    /** Form body for refreshing an expired access token (matches opencode/Codex). */
    fun refreshForm(refreshToken: String): Map<String, String> = linkedMapOf(
        "grant_type" to "refresh_token",
        "client_id" to CLIENT_ID,
        "refresh_token" to refreshToken,
    )

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
        val access = obj["access_token"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val refresh = obj["refresh_token"].stringOrNull()?.takeIf { it.isNotBlank() }
            ?: priorRefresh
        val expiresIn = obj["expires_in"].intValueOrNull()?.toLong()?.takeIf { it > 0 }
            ?: DEFAULT_EXPIRES_IN_SEC
        val idToken = obj["id_token"].stringOrNull().orEmpty()
        // The account id can live in the id_token or, failing that, the access token.
        val account = (accountId(idToken) ?: accountId(access)).orEmpty()
        return OpenAiTokens(
            accessToken = access,
            refreshToken = refresh,
            accountId = account,
            expiresAtEpochSec = nowEpochSec + expiresIn,
        )
    }

    /**
     * Reads the ChatGPT account id from a JWT, trying (in opencode's order) the
     * top-level `chatgpt_account_id`, the `https://api.openai.com/auth` claim, then
     * the first organization id. Returns null when the token isn't a JWT or carries
     * none of them.
     */
    fun accountId(token: String): String? {
        val claims = decodeJwtPayload(token) ?: return null
        claims[ACCOUNT_ID_CLAIM].stringOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        claims[AUTH_CLAIM].asObject()?.get(ACCOUNT_ID_CLAIM).stringOrNull()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return claims["organizations"].asArray()
            ?.firstOrNull().asObject()
            ?.get("id").stringOrNull()?.takeIf { it.isNotBlank() }
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
