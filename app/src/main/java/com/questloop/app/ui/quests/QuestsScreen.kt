package com.questloop.app.ui.quests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.components.CategoryDot
import com.questloop.app.ui.components.DifficultyPips
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.QuestCompletionControls
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestFrequency

@Composable
fun QuestsScreen(
    viewModel: QuestsViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenBank: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Quest?>(null) }

    LaunchedEffect(state.toastId) {
        val message = state.toast ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (state.pendingUndo != null) "Undo" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLast() else viewModel.consumeToast()
    }

    if (state.loading && state.groups.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedButton(
                onClick = onOpenBank,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) { Text("Browse quest bank") }
        }

        if (state.total == 0) {
            item {
                InfoCard(
                    title = "No quests yet",
                    body = "Tap + to add one, or use quick add to turn a list into quests.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        } else if (state.groups.isEmpty()) {
            item {
                InfoCard(
                    title = "All done for today ✓",
                    body = "Your recurring quests will be back tomorrow.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }

        state.groups.forEach { group ->
            item(key = "header-${group.title}") {
                Text(
                    "${group.title} · ${group.items.size}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(group.items, key = { it.quest.id }) { status ->
                QuestBacklogRow(
                    status = status,
                    onComplete = { viewModel.complete(status.quest) },
                    onSkip = { viewModel.skip(status.quest) },
                    onMeasured = { viewModel.completeMeasured(status.quest, it) },
                    onDelete = { pendingDelete = status.quest },
                )
            }
        }

        item { Spacer(Modifier.padding(24.dp)) }
    }

    pendingDelete?.let { quest ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this quest?") },
            text = { Text("\"${quest.title}\" will be removed from your list. Your past history stays.") },
            confirmButton = {
                Button(onClick = { viewModel.delete(quest); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QuestBacklogRow(
    status: QuestRepository.QuestStatus,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onMeasured: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val quest = status.quest
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryDot(quest.category)
                Column(Modifier.weight(1f)) {
                    Text(quest.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Row(
                        Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DifficultyPips(quest.difficulty)
                        Text(
                            "${frequencyLabel(quest.frequency)} · ${quest.estimatedMinutes}m",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${quest.title}")
                }
            }
            // Real completion control per quest style (counting, timed, rating, binary).
            QuestCompletionControls(
                quest = quest,
                progress = status.progress,
                onComplete = onComplete,
                onSkip = onSkip,
                onMeasured = onMeasured,
            )
        }
    }
}

private fun frequencyLabel(frequency: QuestFrequency): String = when (frequency) {
    QuestFrequency.DAILY -> "Daily"
    QuestFrequency.WEEKLY -> "Weekly"
    QuestFrequency.MONTHLY -> "Monthly"
    QuestFrequency.RECURRING -> "Recurring"
    QuestFrequency.ONE_OFF -> "One-off"
    QuestFrequency.SEASONAL -> "Seasonal"
}
