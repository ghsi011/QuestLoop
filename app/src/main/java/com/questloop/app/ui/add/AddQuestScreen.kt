package com.questloop.app.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.data.CalendarEventSummary
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pickableCategories
import com.questloop.app.ui.components.pickableFrequencies
import com.questloop.app.ui.components.pretty
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
        ChipGroup(pickableCategories, draft.category) { sel -> viewModel.updateDraft { it.copy(category = sel) } }

        Text("Difficulty", fontWeight = FontWeight.SemiBold)
        ChipGroup(Difficulty.entries, draft.difficulty) { sel ->
            viewModel.updateDraft { it.copy(difficulty = sel, minutes = Quest.defaultMinutes(sel)) }
        }

        Text("Priority", fontWeight = FontWeight.SemiBold)
        ChipGroup(Priority.entries, draft.priority) { sel -> viewModel.updateDraft { it.copy(priority = sel) } }

        Text("Frequency", fontWeight = FontWeight.SemiBold)
        ChipGroup(pickableFrequencies, draft.frequency) { sel -> viewModel.updateDraft { it.copy(frequency = sel) } }

        ScheduleEditor(
            frequency = draft.frequency,
            scheduledTimes = draft.scheduledTimes,
            scheduledDayOfWeek = draft.scheduledDayOfWeek,
            scheduledDayOfMonth = draft.scheduledDayOfMonth,
            totalOccurrences = draft.totalOccurrences,
            remindersEnabled = draft.remindersEnabled,
            onTimesChange = { v -> viewModel.updateDraft { it.copy(scheduledTimes = v) } },
            onDayOfWeekChange = { v -> viewModel.updateDraft { it.copy(scheduledDayOfWeek = v) } },
            onDayOfMonthChange = { v -> viewModel.updateDraft { it.copy(scheduledDayOfMonth = v) } },
            onTotalOccurrencesChange = { v -> viewModel.updateDraft { it.copy(totalOccurrences = v) } },
            onRemindersChange = { v -> viewModel.updateDraft { it.copy(remindersEnabled = v) } },
        )

        Text("How is it completed?", fontWeight = FontWeight.SemiBold)
        ChipGroup(CompletionStyle.entries, draft.completionStyle) { sel ->
            viewModel.updateDraft { it.copy(completionStyle = sel) }
        }

        if (draft.completionStyle == CompletionStyle.QUANTITATIVE) {
            NumberField(
                value = draft.targetCount,
                onValue = { n -> viewModel.updateDraft { it.copy(targetCount = n) } },
                label = "Target count",
                range = 1..1000,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.unit,
                onValueChange = { v -> viewModel.updateDraft { it.copy(unit = v) } },
                label = { Text("Unit (e.g. glasses, pages)") },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        NumberField(
            value = draft.minutes,
            onValue = { n -> viewModel.updateDraft { it.copy(minutes = n) } },
            label = if (draft.completionStyle == CompletionStyle.DURATION) "Target minutes" else "Estimated minutes",
            range = 1..1440,
            modifier = Modifier.fillMaxWidth(),
        )

        // Over-completion only makes sense for measured (count/duration) quests.
        if (draft.completionStyle == CompletionStyle.QUANTITATIVE ||
            draft.completionStyle == CompletionStyle.DURATION
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow logging past the target", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Keep it on your list after you hit the goal (e.g. a 3rd swim on \"2×/week\"). Resets each interval.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.allowOverCompletion,
                    onCheckedChange = { on -> viewModel.updateDraft { it.copy(allowOverCompletion = on) } },
                )
            }
        }

        DeadlineSection(
            deadlineEpochDay = draft.deadlineEpochDay,
            calendarEvents = state.calendarEvents,
            loadingEvents = state.loadingCalendarEvents,
            onPickDate = viewModel::setDeadline,
            onClearDeadline = { viewModel.setDeadline(null) },
            onOpenCalendarPicker = viewModel::loadCalendarEvents,
            onPickEvent = viewModel::pickDeadlineFromEvent,
        )

        Button(
            onClick = { viewModel.addQuest(onDone) },
            enabled = draft.title.isNotBlank() && !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add quest") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // Both AI tools below feed the shared "Review suggestions" list, so they're
        // grouped together above it — the brain-dump generator sits directly next to
        // its results, with the goal breakdown alongside rather than wedged between.
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

private val deadlineDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/**
 * Optional due date (SPEC §10): pick any date, or pick straight from an upcoming
 * calendar event (which also tags the quest `calendar` and offers its title to
 * pre-fill a still-blank Title field). Calendar permission is requested once, in
 * Settings — here we just show what's available or a hint to enable it there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineSection(
    deadlineEpochDay: Long?,
    calendarEvents: List<CalendarEventSummary>,
    loadingEvents: Boolean,
    onPickDate: (Long) -> Unit,
    onClearDeadline: () -> Unit,
    onOpenCalendarPicker: () -> Unit,
    onPickEvent: (CalendarEventSummary) -> Unit,
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showEventPicker by remember { mutableStateOf(false) }

    Text("Deadline (optional)", fontWeight = FontWeight.SemiBold)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            deadlineEpochDay?.let { "Due ${LocalDate.ofEpochDay(it).format(deadlineDateFormat)}" } ?: "No deadline",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (deadlineEpochDay != null) {
            TextButton(onClick = onClearDeadline) { Text("Clear") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showDatePicker = true }) { Text("Pick date") }
        OutlinedButton(onClick = { onOpenCalendarPicker(); showEventPicker = true }) { Text("From calendar") }
    }

    if (showDatePicker) {
        // Material3's DatePickerState works in UTC-midnight millis by contract,
        // regardless of the device's zone — converting through ZoneOffset.UTC
        // (not the local zone) is what keeps the picked date exactly the day shown.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.ofEpochDay(deadlineEpochDay ?: LocalDate.now().toEpochDay())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onPickDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showEventPicker) {
        AlertDialog(
            onDismissRequest = { showEventPicker = false },
            title = { Text("Pick an event") },
            text = {
                when {
                    loadingEvents -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    calendarEvents.isEmpty() -> Text(
                        "No upcoming events found. Turn on calendar access in Settings, or pick a date manually.",
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(calendarEvents, key = { it.id }) { event ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPickEvent(event)
                                        showEventPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(event.title, modifier = Modifier.weight(1f, fill = false))
                                Text(
                                    LocalDate.ofEpochDay(event.epochDay).format(deadlineDateFormat),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEventPicker = false }) { Text("Close") } },
        )
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
            ChipGroup(pickableCategories, quest.category) {
                onChange(quest.copy(category = it, isReductionQuest = it == QuestCategory.BAD_HABIT_REDUCTION))
            }

            Text("Difficulty (sets XP)", style = MaterialTheme.typography.labelMedium)
            ChipGroup(Difficulty.entries, quest.difficulty) { onChange(quest.copy(difficulty = it)) }

            Text("Frequency", style = MaterialTheme.typography.labelMedium)
            ChipGroup(pickableFrequencies, quest.frequency) { onChange(quest.copy(frequency = it)) }

            ScheduleEditor(
                frequency = quest.frequency,
                scheduledTimes = quest.scheduledTimes,
                scheduledDayOfWeek = quest.scheduledDayOfWeek,
                scheduledDayOfMonth = quest.scheduledDayOfMonth,
                totalOccurrences = quest.totalOccurrences,
                remindersEnabled = quest.remindersEnabled,
                onTimesChange = { onChange(quest.copy(scheduledTimes = it)) },
                onDayOfWeekChange = { onChange(quest.copy(scheduledDayOfWeek = it)) },
                onDayOfMonthChange = { onChange(quest.copy(scheduledDayOfMonth = it)) },
                onTotalOccurrencesChange = { onChange(quest.copy(totalOccurrences = it)) },
                onRemindersChange = { onChange(quest.copy(remindersEnabled = it)) },
            )

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
                    NumberField(
                        value = quest.targetCount ?: 8,
                        onValue = { n -> onChange(quest.copy(targetCount = n)) },
                        label = "Target",
                        range = 1..1000,
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

            NumberField(
                value = quest.estimatedMinutes,
                onValue = { n -> onChange(quest.copy(estimatedMinutes = n)) },
                label = if (quest.completionStyle == CompletionStyle.DURATION) "Target minutes" else "Estimated minutes",
                range = 1..1440,
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
    // Some stored/AI values are valid but not offered as choices (e.g. a seasonal
    // cadence) — keep the active one visible so the row never looks unselected.
    val shown = if (selected in options) options else options + selected
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { option ->
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
    is QuestFrequency -> value.pretty()
    is CompletionStyle -> value.pretty()
    is Enum<*> -> value.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    else -> value.toString()
}

/**
 * Whole-number input that holds its own editable text so a value can be cleared
 * and retyped freely. The model ([value]) only updates when the text parses to a
 * number, clamped to [range]; an empty/partial field is allowed mid-edit instead
 * of snapping back to the last value (which made deleting a digit feel broken).
 * The text is re-seeded only when [value] changes from outside (e.g. a difficulty
 * default), not on every keystroke, so deletion isn't fought. When a typed number
 * is out of range, the field shows the clamped value (so it never displays a
 * number different from what will be saved, even if the model value is unchanged).
 */
@Composable
internal fun NumberField(
    value: Int,
    onValue: (Int) -> Unit,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    val maxLen = range.last.toString().length
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(maxLen)
            val parsed = digits.toIntOrNull()
            if (parsed == null) {
                text = digits // empty / mid-edit — allow it
            } else {
                val clamped = parsed.coerceIn(range.first, range.last)
                // Keep the display in sync with what will be saved: show exactly what
                // was typed when it's in range, but the clamped value when it isn't
                // (we can't rely on a re-seed — the model value may not change).
                text = if (clamped == parsed) digits else clamped.toString()
                onValue(clamped)
            }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
