package com.questloop.core.ai

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for suspending work: rethrows [CancellationException] so a
 * cancelled coroutine keeps cancelling (structured concurrency) instead of the
 * cancellation being repackaged as an ordinary failure ("AI request failed…")
 * with control continuing in a cancelled scope — the same idiom as the app
 * layer's `launchSafely`. Use this, not `runCatching`, around suspend calls.
 */
internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
