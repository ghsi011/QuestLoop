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
import com.questloop.app.ui.components.pickableCategories
import com.questloop.app.ui.components.pickableFrequencies
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val historyDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

@Composable
fun CompletedScreen(viewModel: CompletedViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.messageId) {
        val msg = state.message ?: return@LaunchedEffect
        // Consume before showing: the effect re-runs on re-entering composition, so
        // an unconsumed message (left by navigating away mid-snackbar) would replay.
        viewModel.consumeMessage()
        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
    }

    // Stateless body so it's driveable on the JVM (Robolectric) without a VM/lifecycle.
    CompletedContent(
        state = state,
        onSetFilter = viewModel::setFilter,
        onUndo = viewModel::undo,
        onStartEdit = viewModel::startEdit,
        onReadd = viewModel::readd,
        onSaveEdit = viewModel::saveEdit,
        onCancelEdit = viewModel::cancelEdit,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompletedContent(
    state: CompletedUiState,
    onSetFilter: (HistoryFilter) -> Unit,
    onUndo: (QuestRepository.CompletedEntry) -> Unit,
    onStartEdit: (QuestRepository.CompletedEntry) -> Unit,
    onReadd: (QuestRepository.CompletedEntry) -> Unit,
    onSaveEdit: (Quest) -> Unit,
    onCancelEdit: () -> Unit,
) {
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
                        onClick = { onSetFilter(f) },
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
                onUndo = { onUndo(entry) },
                onEdit = { onStartEdit(entry) },
                onReadd = { onReadd(entry) },
            )
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }

    state.editing?.let { target ->
        EditQuestDialog(
            original = target.quest,
            onDismiss = onCancelEdit,
            onSave = onSaveEdit,
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
                val xp = entry.record.xpAwarded
                if (xp != 0L) {
                    Text(
                        // Skips carry a small negative grant; state it plainly, not
                        // in an alarm color (non-shaming).
                        (if (xp > 0) "+$xp" else "$xp") + " XP",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (xp > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            // Skips are listed too (so they can still be undone later); name the
            // outcome with the same word the button used.
            val outcome = when {
                entry.record.result != CompletionResult.SKIPPED -> null
                entry.record.category == QuestCategory.BAD_HABIT_REDUCTION -> "Slipped"
                else -> "Skipped"
            }
            Text(
                listOfNotNull(
                    LocalDate.ofEpochDay(entry.record.epochDay).format(historyDateFormat),
                    entry.record.difficulty.pretty(),
                    outcome,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onUndo) { Text("Undo") }
                // Edit/Re-add only apply to stored quests (not derived habit/goal
                // quests, routines, admin steps, or a definition that's since gone).
                TextButton(onClick = onEdit, enabled = entry.editable) { Text("Edit") }
                TextButton(onClick = onReadd, enabled = entry.editable) { Text("Re-add") }
            }
        }
    }
}

/**
 * Compact full-quest editor. Changing difficulty/priority/category re-scores the
 * clicked completion's XP; title/frequency changes update the live quest. Fields
 * are kept minimal — the heavier measured-quest fields live on the Add screen.
 */
/**
 * Compact full-quest editor. Changing difficulty/priority/category re-scores the
 * clicked completion's XP; title/frequency changes update the live quest. The thin
 * [AlertDialog] shell can't be driven under Robolectric (its Dialog window OOMs), so
 * all the field logic lives in the window-free [EditQuestFields], which IS unit-tested.
 */
@Composable
private fun EditQuestDialog(original: Quest, onDismiss: () -> Unit, onSave: (Quest) -> Unit) {
    // The latest composed edit, updated by the fields; the Save button reads it.
    var edited by remember(original.id) { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit quest") },
        text = { EditQuestFields(original, onChange = { edited = it }) },
        confirmButton = {
            TextButton(enabled = edited.title.isNotBlank(), onClick = { onSave(edited) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The editable field body (title + difficulty/priority/category/frequency chips).
 * Owns its own field state and emits the composed [Quest] via [onChange] on every
 * edit, so the enclosing dialog's Save can read the latest without a Dialog window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditQuestFields(original: Quest, onChange: (Quest) -> Unit) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var difficulty by remember(original.id) { mutableStateOf(original.difficulty) }
    var priority by remember(original.id) { mutableStateOf(original.priority) }
    var category by remember(original.id) { mutableStateOf(original.category) }
    var frequency by remember(original.id) { mutableStateOf(original.frequency) }

    fun emit() = onChange(
        original.copy(
            title = title.trim(),
            difficulty = difficulty,
            priority = priority,
            category = category,
            frequency = frequency,
            isReductionQuest = category == QuestCategory.BAD_HABIT_REDUCTION,
        ),
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = title,
            onValueChange = { title = it; emit() },
            label = { Text("Title") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Difficulty (sets XP)", style = MaterialTheme.typography.labelMedium)
        ChipRow(Difficulty.entries, difficulty, { it.pretty() }) { difficulty = it; emit() }
        Text("Priority", style = MaterialTheme.typography.labelMedium)
        ChipRow(Priority.entries, priority, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { priority = it; emit() }
        Text("Category", style = MaterialTheme.typography.labelMedium)
        ChipRow(pickableCategories, category, { it.pretty() }) { category = it; emit() }
        Text("Frequency", style = MaterialTheme.typography.labelMedium)
        ChipRow(pickableFrequencies, frequency, { it.pretty() }) { frequency = it; emit() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    // Some stored/AI values are valid but not offered as choices (e.g. a seasonal
    // cadence) — keep the active one visible so the row never looks unselected.
    val shown = if (selected in options) options else options + selected
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}
