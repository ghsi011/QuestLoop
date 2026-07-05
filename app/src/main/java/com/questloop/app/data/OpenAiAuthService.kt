package com.questloop.app.data

import com.questloop.core.ai.openai.OpenAiOAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Drives the "Sign in with ChatGPT" OAuth flow. Abstracted as an interface so the
 * repository can be unit-tested with a fake (the real one needs a browser + socket).
 */
interface OpenAiAuth {
    /**
     * Runs the authorization-code + PKCE handshake. [openUrl] is invoked with the
     * browser URL the user must visit. Suspends until the loopback callback arrives
     * or [timeoutMs] elapses. All failures come back as [Result.failure].
     *
     * The loopback wait is cancellation-aware: cancelling the caller unblocks the
     * listener and frees its port for a retry. But once the browser has been
     * answered with the success page, the handshake is completed even if the
     * caller was cancelled meanwhile — implementations invoke [onTokens] with the
     * fresh tokens before returning success, so callers persist them there (a
     * cancelled caller never receives the returned [Result], only side effects).
     */
    suspend fun signIn(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onTokens: suspend (OpenAiOAuth.OpenAiTokens) -> Unit = {},
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
        onTokens: suspend (OpenAiOAuth.OpenAiTokens) -> Unit,
        openUrl: (String) -> Unit,
    ): Result<OpenAiOAuth.OpenAiTokens> {
        val pkce = OpenAiOAuth.generatePkce()
        val state = OpenAiOAuth.randomUrlSafe(16)
        // Set the moment the browser has been answered with the success page; read
        // back when cancellation races that answer, so a handshake the user watched
        // complete isn't silently dropped.
        val served = AtomicReference<Callback?>(null)
        val callback = try {
            withContext(Dispatchers.IO) {
                ServerSocket().use { server ->
                    // Reuse the address so a quick retry after an aborted sign-in doesn't
                    // fail with "address already in use" while the port drains.
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), OpenAiOAuth.REDIRECT_PORT), 1)
                    openUrl(OpenAiOAuth.authorizeUrl(pkce.challenge, state))
                    val cb = awaitCallback(server, timeoutMs, state) ?: error("Sign-in timed out. Please try again.")
                    served.set(cb)
                    cb
                }
            }
        } catch (e: CancellationException) {
            // Cancelled (screen closed) while waiting: the listener is closed, so
            // port 1455 is free for a retry. If the browser was already told
            // "You're signed in", honor that handshake below instead of dropping it.
            served.get() ?: throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        // From here the browser tab already shows the outcome, so finish the code
        // exchange (and hand the tokens to [onTokens]) even if our caller was
        // cancelled meanwhile: a cancelled caller never sees the returned Result,
        // only the side effects.
        return withContext(Dispatchers.IO + NonCancellable) {
            val code = callback.code
            runCatching {
                when {
                    callback.error != null -> error("Sign-in was declined. Please try again.")
                    callback.state != state -> error("Sign-in could not be verified. Please try again.")
                    code.isNullOrBlank() -> error("Sign-in didn't return an authorization code.")
                    else -> exchange(code, pkce.verifier).getOrThrow().also { onTokens(it) }
                }
            }
        }
    }

    override suspend fun refresh(tokens: OpenAiOAuth.OpenAiTokens): Result<OpenAiOAuth.OpenAiTokens> =
        withContext(Dispatchers.IO) {
            postForm(OpenAiOAuth.refreshForm(tokens.refreshToken), priorRefresh = tokens.refreshToken)
                .map { if (it.accountId.isBlank()) it.copy(accountId = tokens.accountId) else it }
        }

    private suspend fun exchange(code: String, verifier: String): Result<OpenAiOAuth.OpenAiTokens> =
        postForm(OpenAiOAuth.tokenExchangeForm(code, verifier), priorRefresh = "")

    private suspend fun postForm(form: Map<String, String>, priorRefresh: String): Result<OpenAiOAuth.OpenAiTokens> =
        try {
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder()
                .url(tokenEndpoint)
                .addHeader("User-Agent", OpenAiOAuth.USER_AGENT)
                .post(body)
                .build()
            // awaitReply (not execute) so cancelling the caller aborts the call
            // instead of blocking an IO thread until the read timeout.
            val reply = http.newCall(request).awaitReply()
            if (!reply.isSuccessful) {
                throw IOException("OpenAI sign-in failed (${reply.code}).")
            }
            Result.success(
                OpenAiOAuth.parseTokenResponse(reply.body, nowEpochSec(), priorRefresh)
                    ?: throw IOException("OpenAI sign-in returned an unreadable response."),
            )
        } catch (c: CancellationException) {
            // Rethrow so a cancelled refresh keeps cancelling instead of being
            // repackaged as a failure the caller mistakes for "sign-in expired"
            // (refresh runs under IO, not NonCancellable). Same idiom as :core's
            // runCatchingCancellable and the app's launchSafely.
            throw c
        } catch (t: Throwable) {
            Result.failure(t)
        }

    private data class Callback(val path: String, val code: String?, val state: String?, val error: String?)

    /**
     * True only for the request we actually care about: our redirect [path], our
     * random [state] echoed back, and an OAuth `code`/`error` present. Anything else
     * — a favicon fetch, a connectivity probe, or a co-installed app hitting
     * 127.0.0.1:1455 with a forged `?error=` / a `?code=&state=wrong` — is a stray
     * we keep listening past (see [awaitCallback]) rather than let it abort a genuine
     * in-flight sign-in. Matching [state] (128-bit random, never leaves the app) is
     * what makes a forged callback infeasible; a hostile app that pre-binds port 1455
     * before us is the residual local-DoS loopback OAuth can't fully close.
     */
    private fun isOurCallback(cb: Callback, state: String): Boolean =
        cb.path == OpenAiOAuth.CALLBACK_PATH && cb.state == state && (cb.code != null || cb.error != null)

    /**
     * Waits (until [timeoutMs] elapses) for the loopback request that carries our
     * OAuth callback. Requests that aren't [isOurCallback] (wrong path, mismatched or
     * missing `state`, no `code`/`error`) are answered with a neutral page and skipped
     * so a stray or forged request can't abort the flow.
     *
     * A blocking [ServerSocket.accept] can't be interrupted by coroutine
     * cancellation, so the wait polls in slices of at most [ACCEPT_POLL_MS] and
     * re-checks cancellation between them: a cancelled sign-in (screen closed)
     * unblocks within a slice and the caller's `use` releases port 1455 right
     * away instead of holding it until the deadline.
     */
    private suspend fun awaitCallback(server: ServerSocket, timeoutMs: Long, state: String): Callback? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            server.soTimeout = remaining.coerceAtMost(ACCEPT_POLL_MS).toInt()
            val cb = acceptOnce(server, deadline, state) ?: continue // slice elapsed → re-check cancellation/deadline
            if (isOurCallback(cb, state)) return cb
            // else: a stray/forged/slow request — keep waiting for the real callback.
        }
    }

    /**
     * Accepts one loopback request, parses its target, and replies with a page: the
     * "You're signed in" page only for our genuine, state-matched success callback,
     * and a neutral "return to QuestLoop" page for everything else — so a declined,
     * forged, or stray request never claims a sign-in that didn't happen.
     *
     * Returns null only when [ServerSocket.accept] itself times out (poll slice
     * elapsed; the caller re-checks cancellation and the overall deadline). A
     * connection that stalls without sending a request line is bounded by a
     * per-socket read timeout and skipped (empty [Callback]) so it can't hang
     * the sign-in past the deadline.
     */
    private fun acceptOnce(server: ServerSocket, deadline: Long, state: String): Callback? {
        val socket = try {
            server.accept()
        } catch (e: SocketTimeoutException) {
            return null
        }
        return socket.use {
            // accept()'s timeout does NOT cover reading the request, so bound the read
            // too — otherwise a client that connects but never sends a line blocks
            // forever. Recompute against the deadline (accept may have eaten most of
            // the budget) and cap it so one stalled client can't hold the whole window.
            it.soTimeout = (deadline - System.currentTimeMillis()).coerceIn(1L, READ_TIMEOUT_MS).toInt()
            val requestLine = try {
                // e.g. "GET /auth/callback?code=...&state=... HTTP/1.1"
                BufferedReader(InputStreamReader(it.getInputStream())).readLine().orEmpty()
            } catch (e: SocketTimeoutException) {
                return@use Callback("", null, null, null) // stalled client → skip, keep listening
            }
            // "GET /auth/callback?code=...&state=... HTTP/1.1" → target "/auth/callback?code=..."
            val target = requestLine.substringAfter(' ', "").substringBefore(' ')
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', ""))
            val cb = Callback(path = path, code = params["code"], state = params["state"], error = params["error"])
            // Success page only for the real, state-matched, code-bearing callback;
            // a matched-but-declined provider redirect and every stray get the neutral page.
            val response = if (isOurCallback(cb, state) && cb.error == null && !cb.code.isNullOrBlank()) {
                SUCCESS_RESPONSE
            } else {
                NEUTRAL_RESPONSE
            }
            it.getOutputStream().use { out ->
                out.write(response.toByteArray())
                out.flush()
            }
            cb
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.contains('=') }.associate {
            val (k, v) = it.split('=', limit = 2)
            decode(k) to decode(v)
        }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    companion object {
        // Upper bound for a single blocking accept() wait. Coroutine cancellation
        // can't interrupt accept(), so the wait is sliced and cancellation is
        // re-checked between slices — a cancelled sign-in frees port 1455 within
        // about a second instead of holding it until the deadline.
        private const val ACCEPT_POLL_MS = 1_000L

        // Upper bound for reading a single loopback request line; keeps one stalled
        // connection from eating the whole sign-in window while still leaving time
        // to accept the genuine browser callback.
        private const val READ_TIMEOUT_MS = 10_000L

        private val SUCCESS_PAGE =
            "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">" +
                "<h2>You're signed in</h2><p>You can close this tab and return to QuestLoop.</p></body></html>"

        // Served to anything that isn't our genuine success callback (a declined
        // provider redirect, a forged/stray request, a favicon fetch). It makes no
        // claim about sign-in, so a request we didn't honor can't show "You're signed in".
        private val NEUTRAL_PAGE =
            "<!doctype html><html><body style=\"font-family:sans-serif;text-align:center;padding-top:3rem\">" +
                "<p>You can close this tab and return to QuestLoop.</p></body></html>"

        private val SUCCESS_RESPONSE = httpResponse(SUCCESS_PAGE)
        private val NEUTRAL_RESPONSE = httpResponse(NEUTRAL_PAGE)

        private fun httpResponse(page: String): String = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${page.toByteArray().size}\r\n")
            append("Connection: close\r\n\r\n")
            append(page)
        }

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
