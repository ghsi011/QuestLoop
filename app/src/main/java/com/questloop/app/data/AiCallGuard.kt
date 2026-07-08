package com.questloop.app.data

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU awake for the duration of a network call so a slow response isn't
 * dropped when the screen turns off mid-request (e.g. AI quest generation).
 */
interface AiCallGuard {
    suspend fun <T> keepAwake(block: suspend () -> T): T
}

/** No-op default (used in tests / when no Android context is available). */
object NoopAiCallGuard : AiCallGuard {
    override suspend fun <T> keepAwake(block: suspend () -> T): T = block()
}

/**
 * Holds a partial [PowerManager.WakeLock] (CPU on, screen off) around the call,
 * with a safety timeout so it can never leak and drain the battery.
 */
class WakeLockAiCallGuard(
    context: Context,
    // Safety bound only (release happens in `finally`). Must comfortably outlast
    // the slowest provider pipeline — OpenAI's worst case is a token refresh
    // (20s connect + 30s read), the call (20s + 120s), a 401-forced second
    // refresh (50s) and the retried call (140s) ≈ 380s — so the lock can't lapse
    // mid-response, while still capping the battery cost of a leak.
    private val timeoutMs: Long = 480_000L,
) : AiCallGuard {

    private val powerManager = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override suspend fun <T> keepAwake(block: suspend () -> T): T {
        val wakeLock = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuestLoop:ai")?.apply { acquire(timeoutMs) }
        }.getOrNull()
        try {
            return block()
        } finally {
            runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
        }
    }
}
