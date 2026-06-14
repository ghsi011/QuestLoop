package com.questloop.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.QuestCategory

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenHabits: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.prefs
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Settings")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Max quests per day: ${prefs.maxDailyQuests}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = prefs.maxDailyQuests.toFloat(),
                    onValueChange = { viewModel.setMaxDaily(it.toInt()) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
                Text(
                    "How many quests a daily plan can hold.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Default time available: ${prefs.defaultAvailableMinutes} min", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.setAvailableMinutes((prefs.defaultAvailableMinutes - 15).coerceAtLeast(15))
                    }) { Text("−15") }
                    Text("${prefs.defaultAvailableMinutes} min", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = {
                        viewModel.setAvailableMinutes((prefs.defaultAvailableMinutes + 15).coerceAtMost(480))
                    }) { Text("+15") }
                }
                Text(
                    "Used to size your plan when you haven't done an energy check-in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionHeader("Focus areas")
        Text(
            "Quests in these categories get a gentle boost in your daily plan.",
            style = MaterialTheme.typography.bodySmall,
        )
        FocusChips(selected = prefs.focusCategories, onToggle = viewModel::toggleFocus)

        SectionHeader("Habits")
        OutlinedButton(onClick = onOpenHabits, modifier = Modifier.fillMaxWidth()) {
            Text("Manage habits & goals")
        }

        SectionHeader("Privacy")
        InfoCard(
            title = "Your data stays on this device",
            body = "QuestLoop is local-first: nothing is uploaded, and backups are off by default.",
        )
        OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Delete all my data")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all data?") },
            text = { Text("This permanently erases your quests, history, XP, and settings on this device. This can't be undone.") },
            confirmButton = {
                Button(onClick = { confirmDelete = false; viewModel.deleteAllData {} }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FocusChips(selected: Set<QuestCategory>, onToggle: (QuestCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuestCategory.entries.filterNot { it.isMeta }.forEach { category ->
            FilterChip(
                selected = category in selected,
                onClick = { onToggle(category) },
                label = { Text(category.pretty()) },
            )
        }
    }
}
