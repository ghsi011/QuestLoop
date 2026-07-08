package com.questloop.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Shows a ViewModel's one-shot snackbar message, consuming it BEFORE showing.
 *
 * The ordering is the whole point: the effect re-runs whenever the screen
 * re-enters composition (same [messageId]), so a message left unconsumed by
 * navigating away mid-snackbar would otherwise replay on return. Keying on the
 * monotonic [messageId] — never the message string — keeps identical consecutive
 * messages from being swallowed (AGENTS.md one-shot-event rule).
 */
@Composable
fun OneShotSnackbarEffect(
    hostState: SnackbarHostState,
    messageId: Long,
    message: String?,
    consume: () -> Unit,
) {
    LaunchedEffect(messageId) {
        val msg = message ?: return@LaunchedEffect
        consume()
        hostState.showSnackbar(msg, duration = SnackbarDuration.Short)
    }
}

/**
 * The undoable variant of [OneShotSnackbarEffect]: same consume-before-show
 * ordering, plus an "Undo" action when [undo] is present. The undo payload is
 * captured before consuming so the action still works for the snackbar actually
 * on screen — but a replay on re-entry (with a live Undo for a completion the
 * user considers settled) is impossible.
 */
@Composable
fun <T : Any> UndoableSnackbarEffect(
    hostState: SnackbarHostState,
    messageId: Long,
    message: String?,
    undo: T?,
    consume: () -> Unit,
    onUndo: (T) -> Unit,
) {
    LaunchedEffect(messageId) {
        val msg = message ?: return@LaunchedEffect
        val captured = undo
        consume()
        val result = hostState.showSnackbar(
            message = msg,
            actionLabel = if (captured != null) "Undo" else null,
            // Keep undoable messages up longer — ~4s is too short to read the
            // outcome and still reverse a mis-tap.
            duration = if (captured != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && captured != null) onUndo(captured)
    }
}
