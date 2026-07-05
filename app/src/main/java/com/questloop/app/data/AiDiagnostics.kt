package com.questloop.app.data

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * A tiny on-device log of AI request failures the user can export and share for
 * troubleshooting. Entries hold only the model and the error reason, and the
 * caller scrubs credentials (API key, OAuth tokens, Bearer-shaped strings) via
 * [redactSecrets] before recording. Provider error bodies are surfaced verbatim,
 * though, so a line can echo fragments of the request that failed — the log is
 * only ever shared by an explicit user action, never sent anywhere automatically.
 */
interface AiDiagnostics {
    fun record(model: String, message: String)
    fun dump(): String
    fun clear()
}

/** No-op used in tests and when diagnostics aren't wired (keeps the core pure). */
object NoopAiDiagnostics : AiDiagnostics {
    override fun record(model: String, message: String) {}
    override fun dump(): String = ""
    override fun clear() {}
}

/**
 * File-backed diagnostics, capped to the most recent [maxEntries] lines so the
 * log can't grow unbounded.
 */
class FileAiDiagnostics(
    private val context: Context,
    private val maxEntries: Int = 50,
) : AiDiagnostics {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    @Synchronized
    override fun record(model: String, message: String) {
        val line = "${Instant.now()}  [$model]  ${message.replace('\n', ' ').trim()}"
        // Logging an AI error must never itself throw, so guard the read too.
        val existing = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())
        val kept = (existing + line).takeLast(maxEntries)
        runCatching { file.writeText(kept.joinToString("\n")) }
    }

    @Synchronized
    override fun dump(): String = if (file.exists()) file.readText() else ""

    @Synchronized
    override fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "ai_diagnostics.log"
    }
}
