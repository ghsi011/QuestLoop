package com.questloop.app.ui.completed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val historyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletedScreen(viewModel: CompletedViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.messageId) {
        val msg = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        viewModel.consumeMessage()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeader("Completed") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = state.filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(f.label) },
                    )
                }
            }
        }
        if (state.entries.isEmpty() && !state.loading) {
            item {
                Text(
                    "Nothing completed in this window yet — finished quests will show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.entries, key = { it.record.instanceId }) { entry ->
            CompletedCard(
                entry = entry,
                onUndo = { viewModel.undo(entry) },
                onEdit = { viewModel.startEdit(entry) },
                onReadd = { viewModel.readd(entry) },
            )
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }

    state.editing?.let { target ->
        EditQuestDialog(
            original = target.quest,
            onDismiss = viewModel::cancelEdit,
            onSave = viewModel::saveEdit,
        )
    }
}

@Composable
private fun CompletedCard(
    entry: QuestRepository.CompletedEntry,
    onUndo: () -> Unit,
    onEdit: () -> Unit,
    onReadd: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryDot(entry.record.category)
                Text(entry.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (entry.record.xpAwarded != 0L) {
                    Text(
                        "+${entry.record.xpAwarded} XP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "${LocalDate.ofEpochDay(entry.record.epochDay).format(historyDateFormat)} · " +
                    entry.record.difficulty.pretty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUndo) { Text("Undo") }
                // Edit/Re-add need the quest to still exist.
                TextButton(onClick = onEdit, enabled = entry.quest != null) { Text("Edit") }
                TextButton(onClick = onReadd, enabled = entry.quest != null) { Text("Re-add") }
            }
        }
    }
}

/**
 * Compact full-quest editor. Changing difficulty/priority/category re-scores the
 * clicked completion's XP; title/frequency changes update the live quest. Fields
 * are kept minimal — the heavier measured-quest fields live on the Add screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditQuestDialog(original: Quest, onDismiss: () -> Unit, onSave: (Quest) -> Unit) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var difficulty by remember(original.id) { mutableStateOf(original.difficulty) }
    var priority by remember(original.id) { mutableStateOf(original.priority) }
    var category by remember(original.id) { mutableStateOf(original.category) }
    var frequency by remember(original.id) { mutableStateOf(original.frequency) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit quest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Difficulty (sets XP)", style = MaterialTheme.typography.labelMedium)
                ChipRow(Difficulty.entries, difficulty, { it.pretty() }) { difficulty = it }
                Text("Priority", style = MaterialTheme.typography.labelMedium)
                ChipRow(Priority.entries, priority, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { priority = it }
                Text("Category", style = MaterialTheme.typography.labelMedium)
                ChipRow(QuestCategory.entries, category, { it.pretty() }) { category = it }
                Text("Frequency", style = MaterialTheme.typography.labelMedium)
                ChipRow(QuestFrequency.entries, frequency, { it.pretty() }) { frequency = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        original.copy(
                            title = title.trim(),
                            difficulty = difficulty,
                            priority = priority,
                            category = category,
                            frequency = frequency,
                            isReductionQuest = category == QuestCategory.BAD_HABIT_REDUCTION,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
