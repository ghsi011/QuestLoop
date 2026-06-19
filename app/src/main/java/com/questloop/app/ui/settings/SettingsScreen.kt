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
import androidx.compose.foundation.selection.toggleable
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
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.data.AiConfig
import com.questloop.app.data.AiProvider
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

    // (Re)schedule reminder alarms whenever the config changes. Guarded because
    // arming exact alarms can throw if the permission was revoked (Android 12+).
    LaunchedEffect(state.reminders) {
        runCatching { ReminderScheduler(context).apply(state.reminders) }
    }

    // Confirm saves so the user knows a setting took effect. Key on the monotonic
    // messageId, not the string, so an identical confirmation shown twice (e.g.
    // re-tapping a focus chip) isn't swallowed.
    LaunchedEffect(state.messageId) {
        val message = state.savedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.consumeSavedMessage()
    }

    val scope = rememberCoroutineScope()
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Be honest: if denied, reminders won't actually fire.
        if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    "Allow notifications in system settings for reminders to show.",
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    // Pick a previously-exported backup file and restore it.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (json != null) viewModel.importData(json) else viewModel.reportImportError()
        }
    }

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

    // When a ChatGPT sign-in starts, open the authorization URL in the browser.
    // The app's loopback server catches the redirect and finishes the handshake.
    LaunchedEffect(state.authId) {
        val url = state.openAuthUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            snackbarHostState.showSnackbar("Couldn't open a browser to sign in.", duration = SnackbarDuration.Long)
        }
        viewModel.consumeOpenAuthUrl()
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
        AiSection(
            config = state.ai,
            aiBusy = state.aiBusy,
            onSelectProvider = viewModel::setProvider,
            onSaveOpenRouter = viewModel::saveAi,
            onSaveOpenAi = viewModel::saveOpenAi,
            onConnectOpenAi = viewModel::connectOpenAi,
            onDisconnectOpenAi = viewModel::disconnectOpenAi,
        )
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
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import a backup")
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
internal fun AiSection(
    config: AiConfig,
    aiBusy: Boolean,
    onSelectProvider: (AiProvider) -> Unit,
    onSaveOpenRouter: (enabled: Boolean, apiKey: String, model: String, filterWording: Boolean) -> Unit,
    onSaveOpenAi: (enabled: Boolean, model: String, filterWording: Boolean) -> Unit,
    onConnectOpenAi: () -> Unit,
    onDisconnectOpenAi: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Turn messy lists into quests", fontWeight = FontWeight.SemiBold)
            // Pick the backend. OpenRouter uses a key you paste; OpenAI signs you in
            // with your ChatGPT account (no key). Locked while a sign-in is in flight.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.provider == AiProvider.OPENROUTER,
                    onClick = { onSelectProvider(AiProvider.OPENROUTER) },
                    enabled = !aiBusy,
                    label = { Text("OpenRouter") },
                )
                FilterChip(
                    selected = config.provider == AiProvider.OPENAI,
                    onClick = { onSelectProvider(AiProvider.OPENAI) },
                    enabled = !aiBusy,
                    label = { Text("OpenAI (ChatGPT)") },
                )
            }
            when (config.provider) {
                AiProvider.OPENROUTER -> OpenRouterSettings(config, onSaveOpenRouter)
                AiProvider.OPENAI -> OpenAiSettings(
                    config = config,
                    aiBusy = aiBusy,
                    onSave = onSaveOpenAi,
                    onConnect = onConnectOpenAi,
                    onDisconnect = onDisconnectOpenAi,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenRouterSettings(
    config: AiConfig,
    onSave: (enabled: Boolean, apiKey: String, model: String, filterWording: Boolean) -> Unit,
) {
    var enabled by remember(config.provider, config.enabled) { mutableStateOf(config.enabled) }
    var key by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var filterWording by remember(config.filterWording) { mutableStateOf(config.filterWording) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Use OpenRouter for suggestions", fontWeight = FontWeight.SemiBold)
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
        AiConfig.FREE_MODEL_PRESETS.forEach { preset ->
            FilterChip(
                selected = model == preset,
                onClick = { model = preset },
                label = { Text(modelLabel(preset)) },
            )
        }
    }
    FilterWordingRow(filterWording) { filterWording = it }
    Button(
        onClick = { onSave(enabled, key, model, filterWording) },
        enabled = !enabled || key.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save") }
    if (enabled && key.isBlank()) {
        Text(
            "Enter your OpenRouter key to turn on AI.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenAiSettings(
    config: AiConfig,
    aiBusy: Boolean,
    onSave: (enabled: Boolean, model: String, filterWording: Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var enabled by remember(config.provider, config.enabled) { mutableStateOf(config.enabled) }
    var model by remember(config.openAiModel) { mutableStateOf(config.openAiModel) }
    var filterWording by remember(config.filterWording) { mutableStateOf(config.filterWording) }

    Text(
        "Sign in with your ChatGPT account — no API key needed. Your login stays on this device.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (config.openAiConnected) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Signed in to ChatGPT", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
    } else {
        Button(onClick = onConnect, enabled = !aiBusy, modifier = Modifier.fillMaxWidth()) {
            Text(if (aiBusy) "Waiting for the browser…" else "Sign in with ChatGPT")
        }
    }
    OutlinedTextField(
        value = model,
        onValueChange = { model = it },
        label = { Text("Model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AiConfig.OPENAI_MODEL_PRESETS.forEach { preset ->
            FilterChip(
                selected = model == preset,
                onClick = { model = preset },
                label = { Text(preset) },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Use ChatGPT for suggestions", fontWeight = FontWeight.SemiBold)
        Switch(
            checked = enabled,
            onCheckedChange = { enabled = it },
            enabled = config.openAiConnected,
        )
    }
    FilterWordingRow(filterWording) { filterWording = it }
    Button(
        onClick = { onSave(enabled, model, filterWording) },
        enabled = config.openAiConnected,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Save") }
    if (!config.openAiConnected) {
        Text(
            "Sign in to turn on ChatGPT suggestions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun FilterWordingRow(filterWording: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        // Toggleable on the whole row so the label is the switch's accessible
        // name (TalkBack reads "Keep AI wording plain, switch, on").
        Modifier
            .fillMaxWidth()
            .toggleable(value = filterWording, role = Role.Switch) { onChange(it) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Keep AI wording plain", fontWeight = FontWeight.SemiBold)
            Text(
                "Trims flattery and filler from AI summaries. Off shows them word-for-word.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = filterWording, onCheckedChange = null)
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
