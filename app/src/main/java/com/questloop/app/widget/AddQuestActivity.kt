package com.questloop.app.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.QuestLoopApplication
import com.questloop.app.ui.appViewModelFactory
import com.questloop.app.ui.theme.QuestLoopTheme

/**
 * Lightweight, transparent dialog the home-screen widget opens to add a quest.
 * Home-screen widgets can't host an editable field, so the widget's "add" box
 * launches this activity instead; the typed text goes straight through the AI
 * generation flow and is saved as a single one-off quest with no review step
 * (see [QuickAddViewModel]). On success it shows a brief confirmation and closes.
 */
class AddQuestActivity : ComponentActivity() {

    private val viewModel: QuickAddViewModel by viewModels {
        appViewModelFactory((application as QuestLoopApplication).container.repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuestLoopTheme {
                QuickAddDialog(
                    viewModel = viewModel,
                    onAdded = { title ->
                        Toast.makeText(this, "Added “$title”", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }
}

@Composable
private fun QuickAddDialog(
    viewModel: QuickAddViewModel,
    onAdded: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // A saved quest is a one-shot signal: consume it (so it can't replay on a
    // recomposition), then confirm it and close the dialog.
    LaunchedEffect(state.addedTitle) {
        state.addedTitle?.let { title ->
            viewModel.consumeAdded()
            onAdded(title)
        }
    }

    // Focus the field and raise the keyboard as soon as the dialog appears.
    // requestFocus() can throw if the field node isn't placed yet on the first
    // frame, so guard it — losing autofocus is fine, a crash is not.
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }

    // Dismissal stays available even mid-submit — the AI round trip can run for
    // minutes on a bad connection, and trapping the user in a modal over their home
    // screen is worse than an aborted attempt. Leaving is safe: the repository
    // persists the quest non-cancellably, so a dismissal can either abort cleanly
    // before the save or let the already-started save finish in the background.
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Add a quest", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Jot down what's on your mind and we'll add it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.input,
                    onValueChange = viewModel::onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    enabled = !state.submitting,
                    singleLine = true,
                    placeholder = { Text("e.g. Book a dentist appointment") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                    isError = state.error != null,
                    supportingText = state.error?.let { { Text(it) } },
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Always enabled — the user's way out of a slow AI call (see the
                    // Dialog comment above for why leaving mid-submit is safe).
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = viewModel::submit,
                        enabled = !state.submitting && state.input.isNotBlank(),
                    ) {
                        if (state.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Add quest")
                        }
                    }
                }
            }
        }
    }
}
