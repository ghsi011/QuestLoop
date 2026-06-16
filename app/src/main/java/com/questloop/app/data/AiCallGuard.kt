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
    private val timeoutMs: Long = 90_000L,
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
