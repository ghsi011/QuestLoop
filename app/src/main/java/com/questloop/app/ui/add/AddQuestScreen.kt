package com.questloop.app.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency

@Composable
fun AddQuestScreen(viewModel: AddQuestViewModel, onDone: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(QuestCategory.LIFE_ADMIN) }
    var difficulty by remember { mutableStateOf(Difficulty.MEDIUM) }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var frequency by remember { mutableStateOf(QuestFrequency.ONE_OFF) }
    var minutes by remember { mutableIntStateOf(25) }
    var completionStyle by remember { mutableStateOf(CompletionStyle.BINARY) }
    var targetCount by remember { mutableIntStateOf(8) }
    var unit by remember { mutableStateOf("") }
    var quickText by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("New quest")
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("What do you want to get done?") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Category", fontWeight = FontWeight.SemiBold)
        ChipGroup(QuestCategory.entries, category) { category = it }

        Text("Difficulty", fontWeight = FontWeight.SemiBold)
        ChipGroup(Difficulty.entries, difficulty) {
            difficulty = it
            minutes = com.questloop.core.model.Quest.defaultMinutes(it)
        }

        Text("Priority", fontWeight = FontWeight.SemiBold)
        ChipGroup(Priority.entries, priority) { priority = it }

        Text("Frequency", fontWeight = FontWeight.SemiBold)
        ChipGroup(QuestFrequency.entries, frequency) { frequency = it }

        Text("How is it completed?", fontWeight = FontWeight.SemiBold)
        ChipGroup(CompletionStyle.entries, completionStyle) { completionStyle = it }

        if (completionStyle == CompletionStyle.QUANTITATIVE) {
            OutlinedTextField(
                value = targetCount.toString(),
                onValueChange = { v -> targetCount = v.toIntOrNull()?.coerceIn(1, 1000) ?: targetCount },
                label = { Text("Target count") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text("Unit (e.g. glasses, pages)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        OutlinedTextField(
            value = minutes.toString(),
            onValueChange = { v -> minutes = v.toIntOrNull()?.coerceIn(1, 1440) ?: minutes },
            label = { Text(if (completionStyle == CompletionStyle.DURATION) "Target minutes" else "Estimated minutes") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (title.isNotBlank()) {
                    viewModel.addQuest(
                        title = title,
                        category = category,
                        difficulty = difficulty,
                        priority = priority,
                        frequency = frequency,
                        estimatedMinutes = minutes,
                        completionStyle = completionStyle,
                        targetCount = if (completionStyle == CompletionStyle.QUANTITATIVE) targetCount else null,
                        unit = if (completionStyle == CompletionStyle.QUANTITATIVE) unit.ifBlank { null } else null,
                        onDone = onDone,
                    )
                }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add quest") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SectionHeader("Quick add with AI")
        Text(
            "Type a list or just brain-dump what's on your plate. We'll turn it into quests.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = quickText,
            onValueChange = { quickText = it },
            label = { Text("What's on your mind?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
        )
        val generating by viewModel.generating.collectAsStateWithLifecycle()
        val quickResult by viewModel.quickResult.collectAsStateWithLifecycle()
        OutlinedButton(
            onClick = {
                if (quickText.isNotBlank()) {
                    viewModel.quickAddFromText(quickText)
                    quickText = ""
                }
            },
            enabled = quickText.isNotBlank() && !generating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (generating) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Suggest quests ✨")
            }
        }
        quickResult?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
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
