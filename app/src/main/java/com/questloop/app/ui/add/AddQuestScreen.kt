package com.questloop.app.ui.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import androidx.compose.ui.unit.dp
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
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

        OutlinedTextField(
            value = minutes.toString(),
            onValueChange = { v -> minutes = v.toIntOrNull()?.coerceIn(1, 1440) ?: minutes },
            label = { Text("Estimated minutes") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (title.isNotBlank()) {
                    viewModel.addQuest(title, category, difficulty, priority, frequency, minutes, onDone)
                }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add quest") }

        Divider(Modifier.padding(vertical = 8.dp))

        SectionHeader("Quick add from a list")
        Text(
            "Paste todos, one per line. They become safe, easy quests you can refine later.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = quickText,
            onValueChange = { quickText = it },
            label = { Text("One todo per line") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        OutlinedButton(
            onClick = { if (quickText.isNotBlank()) viewModel.quickAddFromText(quickText, onDone) },
            enabled = quickText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Quick add") }
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
