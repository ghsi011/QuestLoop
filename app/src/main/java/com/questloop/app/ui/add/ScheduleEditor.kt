package com.questloop.app.ui.add

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.questloop.app.ui.components.formatMinuteOfDay
import com.questloop.core.generation.QuestSchedule
import com.questloop.core.model.QuestFrequency
import java.time.DayOfWeek
import java.time.format.TextStyle

/**
 * Shared schedule controls for recurring quests (Add form + suggestion review
 * card): times of day, a weekly/monthly anchor day, an occurrence limit ("for 5
 * days" / "for 12 months"), and the per-quest reminder toggle. Renders nothing
 * for non-recurring frequencies — and any values left behind by a frequency
 * switch are scrubbed at save time by [QuestSchedule.normalized].
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditor(
    frequency: QuestFrequency,
    scheduledTimes: List<Int>,
    scheduledDayOfWeek: DayOfWeek?,
    scheduledDayOfMonth: Int?,
    totalOccurrences: Int?,
    remindersEnabled: Boolean,
    onTimesChange: (List<Int>) -> Unit,
    onDayOfWeekChange: (DayOfWeek?) -> Unit,
    onDayOfMonthChange: (Int?) -> Unit,
    onTotalOccurrencesChange: (Int?) -> Unit,
    onRemindersChange: (Boolean) -> Unit,
) {
    if (frequency !in QuestSchedule.schedulableFrequencies) return
    var showTimePicker by remember { mutableStateOf(false) }

    // Posting notifications needs a runtime grant on Android 13+. A denial snaps
    // the toggle back off — a switch that stays on while nothing can ever fire
    // would be lying (the summary would even show a bell).
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (!granted) onRemindersChange(false) }

    // Weekly/monthly quests keep one time (several would convert them to a
    // measured count, which stops the anchor day from gating dueness — see
    // QuestSchedule.normalized); dailies can have up to MAX_TIMES_PER_DAY.
    val maxTimes =
        if (frequency == QuestFrequency.WEEKLY || frequency == QuestFrequency.MONTHLY) 1
        else QuestSchedule.MAX_TIMES_PER_DAY

    Text("Schedule (optional)", fontWeight = FontWeight.SemiBold)

    if (frequency == QuestFrequency.WEEKLY) {
        // Compose-observable locale (plain Locale.getDefault() wouldn't recompose
        // on a locale change — the NonObservableLocale lint error).
        val locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
        Text("On which day?", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = scheduledDayOfWeek == null,
                onClick = { onDayOfWeekChange(null) },
                label = { Text("Any day") },
            )
            DayOfWeek.entries.forEach { day ->
                FilterChip(
                    selected = scheduledDayOfWeek == day,
                    onClick = { onDayOfWeekChange(day) },
                    label = { Text(day.getDisplayName(TextStyle.SHORT, locale)) },
                )
            }
        }
    }

    if (frequency == QuestFrequency.MONTHLY) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("On a set day of the month", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Short months use their last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = scheduledDayOfMonth != null,
                onCheckedChange = { on -> onDayOfMonthChange(if (on) 1 else null) },
            )
        }
        if (scheduledDayOfMonth != null) {
            NumberField(
                value = scheduledDayOfMonth,
                onValue = { onDayOfMonthChange(it) },
                label = "Day of month",
                range = 1..31,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Text(
        if (maxTimes == 1) "Time of day" else "Times of day",
        style = MaterialTheme.typography.labelMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scheduledTimes.forEach { minute ->
            FilterChip(
                selected = true,
                onClick = { onTimesChange(scheduledTimes - minute) },
                label = { Text("${formatMinuteOfDay(minute)}  ✕") },
            )
        }
        if (scheduledTimes.size < maxTimes) {
            FilterChip(
                selected = false,
                onClick = { showTimePicker = true },
                label = { Text("+ Add time") },
            )
        }
    }
    if (scheduledTimes.size > 1) {
        Text(
            "With more than one time, you'll check off each one as you go.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Repeat for a set number of times", style = MaterialTheme.typography.labelMedium)
            Text(
                when (frequency) {
                    QuestFrequency.WEEKLY -> "e.g. 8 = eight completed weeks, then it's done. " +
                        "Weeks you've already completed count too."
                    QuestFrequency.MONTHLY -> "e.g. 12 = twelve completed months, then it's done. " +
                        "Months you've already completed count too."
                    else -> "e.g. 5 = five completed days, then it's done. " +
                        "Days you've already completed count too."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = totalOccurrences != null,
            onCheckedChange = { on -> onTotalOccurrencesChange(if (on) defaultOccurrences(frequency) else null) },
        )
    }
    if (totalOccurrences != null) {
        NumberField(
            value = totalOccurrences,
            onValue = { onTotalOccurrencesChange(it) },
            label = when (frequency) {
                QuestFrequency.WEEKLY -> "How many weeks?"
                QuestFrequency.MONTHLY -> "How many months?"
                else -> "How many days?"
            },
            range = 1..999,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Remind me at these times", style = MaterialTheme.typography.labelMedium)
            if (scheduledTimes.isEmpty()) {
                Text(
                    "Add a time first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = remindersEnabled && scheduledTimes.isNotEmpty(),
            enabled = scheduledTimes.isNotEmpty(),
            onCheckedChange = { on ->
                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                onRemindersChange(on)
            },
        )
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(initialHour = 9, initialMinute = 0)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pick a time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = pickerState.hour * 60 + pickerState.minute
                    onTimesChange((scheduledTimes + minute).distinct().sorted())
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }
}

private fun defaultOccurrences(frequency: QuestFrequency): Int = when (frequency) {
    QuestFrequency.WEEKLY -> 8
    QuestFrequency.MONTHLY -> 12
    else -> 5
}
