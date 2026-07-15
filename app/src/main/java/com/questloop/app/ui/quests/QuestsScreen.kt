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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.components.CategoryDot
import com.questloop.app.ui.components.CompletionSoundEffect
import com.questloop.app.ui.components.DifficultyPips
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.QuestCompletionControls
import com.questloop.app.ui.components.UndoableSnackbarEffect
import com.questloop.app.ui.components.scheduleSummary
import com.questloop.app.ui.add.ScheduleEditor
import com.questloop.core.generation.QuestSchedule
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
    var editingSchedule by remember { mutableStateOf<Quest?>(null) }

    // Celebration chime for the completion behind this toast; same one-shot key.
    CompletionSoundEffect(
        soundId = state.toastId,
        sound = state.sound,
        consume = viewModel::consumeSound,
    )

    UndoableSnackbarEffect(
        hostState = snackbarHostState,
        messageId = state.toastId,
        message = state.toast,
        undo = state.pendingUndo,
        consume = viewModel::consumeToast,
        onUndo = viewModel::undo,
    )

    if (state.loading && state.groups.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("quests-list"),
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
                    enabled = !state.completing,
                    // A finished run has nothing left to log — the row stays for
                    // review/edit/delete only.
                    showControls = group.completable,
                    onComplete = { viewModel.complete(status.quest) },
                    onSkip = { viewModel.skip(status.quest) },
                    onMeasured = { viewModel.completeMeasured(status.quest, it) },
                    onDelete = { pendingDelete = status.quest },
                    onEditSchedule = { editingSchedule = status.quest },
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

    editingSchedule?.let { original ->
        // Local working copy; persisted only on Save (repository normalizes).
        var draft by remember(original.id) { mutableStateOf(original) }
        AlertDialog(
            onDismissRequest = { editingSchedule = null },
            title = { Text("Schedule \"${original.title}\"") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ScheduleEditor(
                        frequency = draft.frequency,
                        scheduledTimes = draft.scheduledTimes,
                        scheduledDayOfWeek = draft.scheduledDayOfWeek,
                        scheduledDayOfMonth = draft.scheduledDayOfMonth,
                        totalOccurrences = draft.totalOccurrences,
                        remindersEnabled = draft.remindersEnabled,
                        onTimesChange = { draft = draft.copy(scheduledTimes = it) },
                        onDayOfWeekChange = { draft = draft.copy(scheduledDayOfWeek = it) },
                        onDayOfMonthChange = { draft = draft.copy(scheduledDayOfMonth = it) },
                        onTotalOccurrencesChange = { draft = draft.copy(totalOccurrences = it) },
                        onRemindersChange = { draft = draft.copy(remindersEnabled = it) },
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.updateQuest(draft); editingSchedule = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSchedule = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QuestBacklogRow(
    status: QuestRepository.QuestStatus,
    enabled: Boolean,
    showControls: Boolean,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onMeasured: (Int) -> Unit,
    onDelete: () -> Unit,
    onEditSchedule: () -> Unit,
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
                    scheduleSummary(quest, status.completedOccurrences)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // Schedule only applies to recurring cadences (see QuestSchedule).
                if (quest.frequency in QuestSchedule.schedulableFrequencies) {
                    IconButton(onClick = onEditSchedule) {
                        Icon(Icons.Outlined.Schedule, contentDescription = "Edit schedule for ${quest.title}")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${quest.title}")
                }
            }
            if (showControls) {
                // Real completion control per quest style (counting, timed, rating, binary).
                QuestCompletionControls(
                    quest = quest,
                    progress = status.progress,
                    onComplete = onComplete,
                    onSkip = onSkip,
                    onMeasured = onMeasured,
                    enabled = enabled,
                )
            }
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
