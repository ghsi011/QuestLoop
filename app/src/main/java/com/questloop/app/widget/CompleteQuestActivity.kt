package com.questloop.app.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.questloop.app.QuestLoopApplication
import com.questloop.app.ui.components.QuestCompletionControls
import com.questloop.app.ui.theme.QuestLoopTheme
import com.questloop.app.util.CompletionSoundPlayer
import com.questloop.core.model.CompletionStyle
import java.time.LocalDate

/**
 * Lightweight, transparent completion menu the home-screen widget opens when a task
 * row is tapped. Shows the quest and a small menu (mark done / skip) that completes
 * it for the day via [QuickCompleteViewModel] — all over the home screen, without
 * opening the app. On success it shows a brief confirmation and closes.
 */
class CompleteQuestActivity : ComponentActivity() {

    private val viewModel: QuickCompleteViewModel by viewModels {
        val repo = (application as QuestLoopApplication).container.repository
        val questId = intent.getStringExtra(EXTRA_QUEST_ID).orEmpty()
        // Credit the day the user actually taps, resolved now — not a day baked into a
        // possibly-stale widget render (which could mis-date a completion across midnight).
        val epochDay = LocalDate.now().toEpochDay()
        viewModelFactory { initializer { QuickCompleteViewModel(repo, questId, epochDay) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuestLoopTheme {
                QuickCompleteDialog(
                    viewModel = viewModel,
                    onDone = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_QUEST_ID = "com.questloop.app.widget.QUEST_ID"
    }
}

@Composable
private fun QuickCompleteDialog(
    viewModel: QuickCompleteViewModel,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Success is a one-shot: consume it (so it can't replay on recomposition), then
    // chime, confirm and close. The player outlives this activity's finish() —
    // playback is process-wide, so the celebration isn't cut off by closing.
    LaunchedEffect(state.doneMessage) {
        state.doneMessage?.let { message ->
            val sound = state.sound
            viewModel.consumeDone()
            sound?.let { CompletionSoundPlayer.play(context, it) }
            onDone(message)
        }
    }

    // Nothing to act on (already completed elsewhere, or archived): close quietly.
    LaunchedEffect(state.notFound) {
        if (state.notFound) {
            onDone("That quest isn't here anymore.")
        }
    }

    // Dismissal stays available even mid-submit — a started complete/skip runs
    // non-cancellably in the ViewModel, so leaving can't drop a "Mark done" the
    // user believes they recorded, and blocking would trap them in a modal if the
    // write ever stalled (e.g. behind an import holding the completion mutex).
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                if (state.loading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.height(24.dp), strokeWidth = 2.dp)
                    }
                    return@Column
                }
                Text("Complete quest", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                // Style-aware controls — the same stepper/rating/binary control the app
                // uses, so a counting/timed/subjective quest can log partial progress from
                // the widget instead of only jumping straight to full credit.
                val quest = state.quest
                if (quest != null) {
                    QuestCompletionControls(
                        quest = quest,
                        progress = state.progress,
                        onComplete = viewModel::complete,
                        onSkip = viewModel::skip,
                        onMeasured = viewModel::logMeasured,
                        enabled = !state.submitting,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    // Always enabled — leaving mid-submit is safe (see the Dialog comment).
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    // BINARY already shows its own Complete/Skip; measured styles get their
                    // skip here so every style can be skipped for the day.
                    if (quest != null && quest.completionStyle != CompletionStyle.BINARY) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = viewModel::skip, enabled = !state.submitting) { Text("Skip today") }
                    }
                }
            }
        }
    }
}
