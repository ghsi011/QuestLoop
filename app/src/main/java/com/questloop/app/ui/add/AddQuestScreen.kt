package com.questloop.app.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency

@Composable
fun AddQuestScreen(viewModel: AddQuestViewModel, onDone: () -> Unit) {
    // The draft lives in the ViewModel (process-scoped), so a half-typed quest
    // survives leaving the Add screen — whose nav entry is popped on a tab switch —
    // and coming back.
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("New quest")
        OutlinedTextField(
            value = draft.title,
            onValueChange = { v -> viewModel.updateDraft { it.copy(title = v) } },
            label = { Text("What do you want to get done?") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Category", fontWeight = FontWeight.SemiBold)
        ChipGroup(QuestCategory.entries, draft.category) { sel -> viewModel.updateDraft { it.copy(category = sel) } }

        Text("Difficulty", fontWeight = FontWeight.SemiBold)
        ChipGroup(Difficulty.entries, draft.difficulty) { sel ->
            viewModel.updateDraft { it.copy(difficulty = sel, minutes = Quest.defaultMinutes(sel)) }
        }

        Text("Priority", fontWeight = FontWeight.SemiBold)
        ChipGroup(Priority.entries, draft.priority) { sel -> viewModel.updateDraft { it.copy(priority = sel) } }

        Text("Frequency", fontWeight = FontWeight.SemiBold)
        ChipGroup(QuestFrequency.entries, draft.frequency) { sel -> viewModel.updateDraft { it.copy(frequency = sel) } }

        Text("How is it completed?", fontWeight = FontWeight.SemiBold)
        ChipGroup(CompletionStyle.entries, draft.completionStyle) { sel ->
            viewModel.updateDraft { it.copy(completionStyle = sel) }
        }

        if (draft.completionStyle == CompletionStyle.QUANTITATIVE) {
            OutlinedTextField(
                value = draft.targetCount.toString(),
                onValueChange = { v ->
                    v.toIntOrNull()?.coerceIn(1, 1000)?.let { n -> viewModel.updateDraft { it.copy(targetCount = n) } }
                },
                label = { Text("Target count") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.unit,
                onValueChange = { v -> viewModel.updateDraft { it.copy(unit = v) } },
                label = { Text("Unit (e.g. glasses, pages)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = draft.minutes.toString(),
            onValueChange = { v ->
                v.toIntOrNull()?.coerceIn(1, 1440)?.let { n -> viewModel.updateDraft { it.copy(minutes = n) } }
            },
            label = { Text(if (draft.completionStyle == CompletionStyle.DURATION) "Target minutes" else "Estimated minutes") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = { viewModel.addQuest(onDone) },
            enabled = draft.title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add quest") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionHeader("Quick add with AI")
        Text(
            "Type a list or just brain-dump what's on your plate. We'll turn it into quests for you to review.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.quickText,
            onValueChange = { v -> viewModel.updateDraft { it.copy(quickText = v) } },
            label = { Text("What's on your mind?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
        OutlinedButton(
            onClick = { if (draft.quickText.isNotBlank()) viewModel.generate(draft.quickText) },
            enabled = draft.quickText.isNotBlank() && !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(if (state.suggestions.isEmpty()) "Suggest quests ✨" else "Regenerate ✨")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionHeader("Break down a goal")
        Text(
            "Name one bigger goal and get a short ladder of steps to review.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = draft.goalText,
            onValueChange = { v -> viewModel.updateDraft { it.copy(goalText = v) } },
            label = { Text("What's the goal?") },
            placeholder = { Text("e.g. run a 10k") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { if (draft.goalText.isNotBlank()) viewModel.decomposeGoal(draft.goalText) },
            enabled = draft.goalText.isNotBlank() && !state.generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.generating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Break into steps ✨")
            }
        }

        state.message?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }

        if (state.suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SectionHeader("Review suggestions", modifier = Modifier.weight(1f))
                Button(onClick = { viewModel.acceptAll() }, enabled = !state.saving) { Text("Add all") }
            }
            state.suggestions.forEach { suggestion ->
                SuggestionCard(
                    quest = suggestion,
                    refining = state.refiningId == suggestion.id,
                    addEnabled = !state.saving,
                    onChange = viewModel::updateSuggestion,
                    onAccept = { viewModel.acceptSuggestion(suggestion.id) },
                    onRemove = { viewModel.removeSuggestion(suggestion.id) },
                    onRefine = { instruction -> viewModel.refineSuggestion(suggestion.id, instruction) },
                )
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    quest: Quest,
    refining: Boolean,
    addEnabled: Boolean,
    onChange: (Quest) -> Unit,
    onAccept: () -> Unit,
    onRemove: () -> Unit,
    onRefine: (String) -> Unit,
) {
    var refineText by remember(quest.id) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = quest.title,
                onValueChange = { onChange(quest.copy(title = it)) },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Category", style = MaterialTheme.typography.labelMedium)
            ChipGroup(QuestCategory.entries, quest.category) {
                onChange(quest.copy(category = it, isReductionQuest = it == QuestCategory.BAD_HABIT_REDUCTION))
            }

            Text("Difficulty (sets XP)", style = MaterialTheme.typography.labelMedium)
            ChipGroup(Difficulty.entries, quest.difficulty) { onChange(quest.copy(difficulty = it)) }

            Text("Frequency", style = MaterialTheme.typography.labelMedium)
            ChipGroup(QuestFrequency.entries, quest.frequency) { onChange(quest.copy(frequency = it)) }

            Text("How is it completed?", style = MaterialTheme.typography.labelMedium)
            ChipGroup(CompletionStyle.entries, quest.completionStyle) {
                onChange(
                    quest.copy(
                        completionStyle = it,
                        targetCount = if (it == CompletionStyle.QUANTITATIVE) (quest.targetCount ?: 8) else null,
                        unit = if (it == CompletionStyle.QUANTITATIVE) quest.unit else null,
                    ),
                )
            }

            if (quest.completionStyle == CompletionStyle.QUANTITATIVE) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = (quest.targetCount ?: 8).toString(),
                        onValueChange = { v -> onChange(quest.copy(targetCount = v.toIntOrNull()?.coerceIn(1, 1000) ?: quest.targetCount)) },
                        label = { Text("Target") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = quest.unit.orEmpty(),
                        onValueChange = { onChange(quest.copy(unit = it.ifBlank { null })) },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OutlinedTextField(
                value = quest.estimatedMinutes.toString(),
                onValueChange = { v -> onChange(quest.copy(estimatedMinutes = v.toIntOrNull()?.coerceIn(1, 1440) ?: quest.estimatedMinutes)) },
                label = { Text(if (quest.completionStyle == CompletionStyle.DURATION) "Target minutes" else "Estimated minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Per-quest AI refine.
            OutlinedTextField(
                value = refineText,
                onValueChange = { refineText = it },
                label = { Text("Ask AI to change this quest") },
                placeholder = { Text("e.g. make it weekly and easier") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (refineText.isNotBlank()) onRefine(refineText) },
                    enabled = refineText.isNotBlank() && !refining,
                ) {
                    if (refining) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Refine ✨")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, enabled = quest.title.isNotBlank() && addEnabled) { Text("Add") }
                OutlinedButton(onClick = onRemove) { Text("Discard") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(options: List<T>, selected: T, label: (T) -> String = { prettyOf(it) }, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

private fun <T> prettyOf(value: T): String = when (value) {
    is QuestCategory -> value.pretty()
    is Enum<*> -> value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    else -> value.toString()
}
