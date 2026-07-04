package com.questloop.app.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An HTTP status code plus its fully-read body (the response is already closed). */
internal data class HttpReply(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Executes the call and reads the whole body, suspending (not blocking) so that
 * cancelling the calling coroutine cancels the OkHttp call. A blocking
 * [Call.execute] inside `withContext(IO)` ignores cancellation: an abandoned
 * request (e.g. leaving the screen mid-generation) would keep an IO thread, the
 * network, and the wake lock busy until the read timeout (up to 120s).
 *
 * The body is read inside the callback — on OkHttp's thread, before resuming —
 * so cancellation also aborts a stalled body read, not just connect/headers.
 */
internal suspend fun Call.awaitReply(): HttpReply = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val reply = runCatching {
                    response.use { HttpReply(it.code, it.body?.string().orEmpty()) }
                }
                // Resuming after cancellation is a no-op; nothing leaks because
                // the response is already closed either way.
                reply.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) },
                )
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { cancel() }
}
