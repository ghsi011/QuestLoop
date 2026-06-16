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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.reminders.ReminderScheduler
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.QuestCategory

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenHabits: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val prefs = state.prefs
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // (Re)schedule reminder alarms whenever the config changes.
    LaunchedEffect(state.reminders) {
        ReminderScheduler(context).apply(state.reminders)
    }

    // Confirm saves so the user knows a setting took effect.
    LaunchedEffect(state.savedMessage) {
        val message = state.savedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.consumeSavedMessage()
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result ignored: notifications simply won't show if denied */ }

    // When an export is ready, hand it to the system share sheet.
    LaunchedEffect(state.exportJson) {
        state.exportJson?.let { json ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "questloop-export.json")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            context.startActivity(Intent.createChooser(intent, "Export QuestLoop data"))
            viewModel.consumeExport()
        }
    }

    // When the AI error log is ready, hand it to the system share sheet.
    LaunchedEffect(state.diagnostics) {
        state.diagnostics?.let { log ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "questloop-ai-log.txt")
                putExtra(Intent.EXTRA_TEXT, log)
            }
            context.startActivity(Intent.createChooser(intent, "Share AI error log"))
            viewModel.consumeDiagnostics()
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Settings")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                // Local while dragging; persisted (with a confirmation) on release so
                // the snackbar fires once, not on every tick.
                var sliderValue by remember(prefs.maxDailyQuests) {
                    mutableIntStateOf(prefs.maxDailyQuests)
                }
                Text("Max quests per day: $sliderValue", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { sliderValue = it.toInt() },
                    onValueChangeFinished = { viewModel.setMaxDaily(sliderValue) },
                    valueRange = 1f..12f,
                    steps = 10,
                )
                Text(
                    "How many quests QuestLoop puts in your daily plan.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Time/day", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    viewModel.setAvailableMinutes((prefs.defaultAvailableMinutes - 15).coerceAtLeast(15))
                }) { Text("−15") }
                Text("${prefs.defaultAvailableMinutes}m", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = {
                    viewModel.setAvailableMinutes((prefs.defaultAvailableMinutes + 15).coerceAtMost(480))
                }) { Text("+15") }
            }
        }

        SectionHeader("Daily reminders")
        RemindersSection(
            config = state.reminders,
            onChange = { updated ->
                if (updated.enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.setReminders(updated)
            },
        )

        SectionHeader("Focus areas")
        Text(
            "Quests in these categories get a gentle boost in your daily plan.",
            style = MaterialTheme.typography.bodySmall,
        )
        FocusChips(selected = prefs.focusCategories, onToggle = viewModel::toggleFocus)

        SectionHeader("AI suggestions")
        AiSection(config = state.ai, onSave = viewModel::saveAi)
        TextButton(onClick = viewModel::shareDiagnostics) {
            Text("Share AI error log")
        }

        SectionHeader("Habits")
        OutlinedButton(onClick = onOpenHabits, modifier = Modifier.fillMaxWidth()) {
            Text("Manage habits & goals")
        }

        SectionHeader("Privacy")
        InfoCard(
            title = "Your data stays on this device",
            body = "Nothing is uploaded.",
        )
        OutlinedButton(onClick = viewModel::requestExport, modifier = Modifier.fillMaxWidth()) {
            Text("Export my data")
        }
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

@Composable
private fun RemindersSection(
    config: com.questloop.app.data.ReminderConfig,
    onChange: (com.questloop.app.data.ReminderConfig) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Morning & evening nudges", fontWeight = FontWeight.SemiBold)
                Switch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
            }
            if (config.enabled) {
                HourRow(
                    label = "Morning",
                    hour = config.morningHour,
                    onHour = { onChange(config.copy(morningHour = it)) },
                )
                HourRow(
                    label = "Evening",
                    hour = config.eveningHour,
                    onHour = { onChange(config.copy(eveningHour = it)) },
                )
            }
            Text(
                "A friendly nudge each morning and evening.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HourRow(label: String, hour: Int, onHour: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onHour((hour - 1).coerceAtLeast(0)) }) { Text("−") }
        Text("%02d:00".format(hour), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onHour((hour + 1).coerceAtMost(23)) }) { Text("+") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSection(
    config: com.questloop.app.data.AiConfig,
    onSave: (enabled: Boolean, apiKey: String, model: String) -> Unit,
) {
    var enabled by remember(config.enabled) { mutableStateOf(config.enabled) }
    var key by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Turn messy lists into quests", fontWeight = FontWeight.SemiBold)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Text(
                "Bring your own key from openrouter.ai. It stays on your device.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                // Entering a key implies you want AI on, so flip the switch for you
                // (you can still turn it back off above).
                onValueChange = { key = it; if (it.isNotBlank()) enabled = true },
                label = { Text("OpenRouter API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.questloop.app.data.AiConfig.FREE_MODEL_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = model == preset,
                        onClick = { model = preset },
                        label = { Text(modelLabel(preset)) },
                    )
                }
            }
            Button(
                onClick = { onSave(enabled, key, model) },
                enabled = !enabled || key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

/** Short, readable label for a model preset chip. */
private fun modelLabel(model: String): String = when (model) {
    "openrouter/free" -> "Auto-pick (free)"
    else -> model.substringAfter('/').substringBefore(':').ifEmpty { model }
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
