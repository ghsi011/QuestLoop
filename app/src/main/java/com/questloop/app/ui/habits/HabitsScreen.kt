package com.questloop.app.ui.habits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.QuestCategory

@Composable
fun HabitsScreen(viewModel: HabitsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Title + the action to run if the user confirms the deletion.
    var pendingDelete by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Habits to build")
        state.habits.forEach { habit ->
            RowItem(
                title = habit.title,
                subtitle = "${habit.category.pretty()} · ${habit.targetPerWeek}×/week",
                onRemove = { pendingDelete = habit.title to { viewModel.removeHabit(habit.id) } },
            )
        }
        AddHabitForm(onAdd = viewModel::addHabit)

        SectionHeader("Habits to reduce")
        state.badHabits.forEach { bad ->
            RowItem(
                title = bad.title,
                subtitle = bad.dailyLimit?.let { "Daily limit: $it" } ?: "Honest tracking",
                onRemove = { pendingDelete = bad.title to { viewModel.removeBadHabit(bad.id) } },
            )
        }
        AddBadHabitForm(onAdd = viewModel::addBadHabit)

        SectionHeader("Goals")
        state.goals.forEach { goal ->
            RowItem(
                title = goal.title,
                subtitle = "${goal.category.pretty()} · weekly progress",
                onRemove = { pendingDelete = goal.title to { viewModel.removeGoal(goal.id) } },
            )
        }
        AddGoalForm(onAdd = viewModel::addGoal)
    }

    pendingDelete?.let { (title, confirm) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove \"$title\"?") },
            text = { Text("This stops generating quests from it. Your past history stays.") },
            confirmButton = {
                Button(onClick = { confirm(); pendingDelete = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowItem(title: String, subtitle: String, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddHabitForm(onAdd: (String, QuestCategory, Int) -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(QuestCategory.HEALTH) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("New habit") },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestCategory.entries.filterNot { it.isMeta || it == QuestCategory.BAD_HABIT_REDUCTION }.forEach { c ->
                    FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c.pretty()) })
                }
            }
            Button(
                onClick = { onAdd(title, category, 7); title = "" },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add habit") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddGoalForm(onAdd: (String, QuestCategory) -> Unit) {
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(QuestCategory.PERSONAL_GROWTH) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("New goal") },
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestCategory.entries.filterNot { it.isMeta || it == QuestCategory.BAD_HABIT_REDUCTION }.forEach { c ->
                    FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c.pretty()) })
                }
            }
            Button(
                onClick = { onAdd(title, category); title = "" },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add goal") }
        }
    }
}

@Composable
private fun AddBadHabitForm(onAdd: (String, Int?) -> Unit) {
    var title by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Habit to reduce") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it.filter(Char::isDigit) },
                label = { Text("Optional daily limit") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onAdd(title, limit.toIntOrNull()); title = ""; limit = "" },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add") }
        }
    }
}
