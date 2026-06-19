package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Drives the "Sign in with ChatGPT" OAuth flow. Abstracted as an interface so the
 * repository can be unit-tested with a fake (the real one needs a browser + socket).
 */
interface OpenAiAuth {
    /**
     * Runs the authorization-code + PKCE handshake. [openUrl] is invoked with the
     * browser URL the user must visit. Suspends until the loopback callback arrives
     * or [timeoutMs] elapses. All failures come back as [Result.failure].
     */
    suspend fun signIn(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        openUrl: (String) -> Unit,
    ): Result<OpenAiOAuth.OpenAiTokens>

    /** Trades a refresh token for a fresh access token. */
    suspend fun refresh(tokens: OpenAiOAuth.OpenAiTokens): Result<OpenAiOAuth.OpenAiTokens>

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60_000L
    }
}

/**
 * On-device implementation, mirroring OpenAI's Codex CLI. The pure protocol bits
 * (PKCE, URL building, token + JWT parsing) live in core's [OpenAiOAuth]; this
 * class owns the side effects: a loopback HTTP server that catches the browser
 * redirect, the OkHttp token calls, and the clock.
 *
 * Loopback (rather than a custom URI scheme) is required because OpenAI's public
 * Codex client is registered with a fixed `http://localhost:1455/auth/callback`
 * redirect. The on-device browser and the app share the loopback interface, so a
 * tiny [ServerSocket] can receive the callback.
 */
class OpenAiAuthService(
    private val http: OkHttpClient = sharedClient,
    private val tokenEndpoint: String = OpenAiOAuth.TOKEN_ENDPOINT,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
) : OpenAiAuth {

    override suspend fun signIn(
        timeoutMs: Long,
        openUrl: (String) -> Unit,
    ): Result<OpenAiOAuth.OpenAiTokens> = withContext(Dispatchers.IO) {
        val pkce = OpenAiOAuth.generatePkce()
        val state = OpenAiOAuth.randomUrlSafe(16)
        runCatching {
            ServerSocket().use { server ->
                // Reuse the address so a quick retry after an aborted sign-in doesn't
                // fail with "address already in use" while the port drains.
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), OpenAiOAuth.REDIRECT_PORT), 1)
                openUrl(OpenAiOAuth.authorizeUrl(pkce.challenge, state))
                val callback = awaitCallback(server, timeoutMs) ?: error("Sign-in timed out. Please try again.")
                when {
                    callback.error != null -> error("Sign-in was declined. Please try again.")
                    callback.state != state -> error("Sign-in could not be verified. Please try again.")
                    callback.code.isNullOrBlank() -> error("Sign-in didn't return an authorization code.")
                    else -> exchange(callback.code, pkce.verifier).getOrThrow()
                }
            }
        }
    }

    override suspend fun refresh(tokens: OpenAiOAuth.OpenAiTokens): Result<OpenAiOAuth.OpenAiTokens> =
        withContext(Dispatchers.IO) {
            postForm(OpenAiOAuth.refreshForm(tokens.refreshToken), priorRefresh = tokens.refreshToken)
                .map { if (it.accountId.isBlank()) it.copy(accountId = tokens.accountId) else it }
        }

    private fun exchange(code: String, verifier: String): Result<OpenAiOAuth.OpenAiTokens> =
        postForm(OpenAiOAuth.tokenExchangeForm(code, verifier), priorRefresh = "")

    private fun postForm(form: Map<String, String>, priorRefresh: String): Result<OpenAiOAuth.OpenAiTokens> =
        runCatching {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder()
                .url(tokenEndpoint)
                .addHeader("User-Agent", OpenAiOAuth.USER_AGENT)
                .post(body)
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("OpenAI sign-in failed (${response.code}).")
                }
                OpenAiOAuth.parseTokenResponse(text, nowEpochSec(), priorRefresh)
                    ?: throw IOException("OpenAI sign-in returned an unreadable response.")
            }
        }

    private data class Callback(val code: String?, val state: String?, val error: String?)

    /**
     * Waits (until [timeoutMs] elapses) for the loopback request that carries the
     * OAuth `code`/`error`. Stray connections without those params (favicon fetches,
     * connectivity probes) are answered and skipped so they don't abort the flow.
     */
    private fun awaitCallback(server: ServerSocket, timeoutMs: Long): Callback? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            server.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val cb = acceptOnce(server, remaining) ?: return null // accept() timed out → overall timeout
            if (cb.code != null || cb.error != null) return cb
            // else: a stray/slow request — keep waiting for the real callback.
        }
    }

    /**
     * Accepts one loopback request, parses its query, and replies with a page.
     * Returns null only when [ServerSocket.accept] itself times out (overall
     * deadline reached). A connection that stalls without sending a request line
     * is bounded by a per-socket read timeout and skipped (empty [Callback]) so it
     * can't hang the sign-in past the deadline.
     */
    private fun acceptOnce(server: ServerSocket, remainingMs: Long): Callback? {
        val socket = try {
            server.accept()
        } catch (e: SocketTimeoutException) {
            return null
        }
        return socket.use {
            // accept()'s timeout does NOT cover reading the request, so bound the read
            // too — otherwise a client that connects but never sends a line blocks forever.
            it.soTimeout = remainingMs.coerceIn(1L, READ_TIMEOUT_MS).toInt()
            val requestLine = try {
                // e.g. "GET /auth/callback?code=...&state=... HTTP/1.1"
                BufferedReader(InputStreamReader(it.getInputStream())).readLine().orEmpty()
            } catch (e: SocketTimeoutException) {
                return@use Callback(null, null, null) // stalled client → skip, keep listening
            }
            val query = requestLine.substringAfter('?', "").substringBefore(' ')
            val params = parseQuery(query)
            it.getOutputStream().use { out ->
                out.write(SUCCESS_RESPONSE.toByteArray())
                out.flush()
            }
            Callback(code = params["code"], state = params["state"], error = params["error"])
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            decode(k) to decode(v)
        }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    companion object {
        // Upper bound for reading a single loopback request line; keeps one stalled
        // connection from eating the whole sign-in window while still leaving time
        // to accept the genuine browser callback.
        private const val READ_TIMEOUT_MS = 10_000L

        private val SUCCESS_PAGE =
            "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">" +
                "<h2>You're signed in</h2><p>You can close this tab and return to QuestLoop.</p></body></html>"

        private val SUCCESS_RESPONSE = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${SUCCESS_PAGE.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(SUCCESS_PAGE)
        }

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
