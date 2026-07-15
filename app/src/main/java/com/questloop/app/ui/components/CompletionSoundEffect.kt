package com.questloop.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.questloop.app.util.CompletionSoundPlayer
import com.questloop.core.reward.CompletionSound

/**
 * Plays a completion's one-shot celebration chime, consuming it BEFORE playing —
 * the same consume-first ordering as [OneShotSnackbarEffect], so re-entering
 * composition (rotation, tab return) can never replay a chime the user already
 * heard. Key it on the same monotonic id that keys the completion snackbar.
 */
@Composable
fun CompletionSoundEffect(soundId: Long, sound: CompletionSound?, consume: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(soundId) {
        val toPlay = sound ?: return@LaunchedEffect
        consume()
        CompletionSoundPlayer.play(context, toPlay)
    }
}
