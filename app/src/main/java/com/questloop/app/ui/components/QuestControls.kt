package com.questloop.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest

/**
 * The completion control for a quest, chosen by its [CompletionStyle]. Shared by
 * the Today plan and the Quests backlog so a counting quest (e.g. glasses of
 * water) or a reduction quest behaves identically wherever it's shown.
 *
 * @param progress today's logged count/minutes, for resuming partial logs.
 * @param onComplete binary "done".
 * @param onSkip binary skip / honest log for reduction quests.
 * @param onMeasured a measured value (count, minutes, or 1..5 rating).
 */
@Composable
fun QuestCompletionControls(
    quest: Quest,
    progress: Int,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onMeasured: (Int) -> Unit,
    enabled: Boolean = true,
) {
    when (quest.completionStyle) {
        CompletionStyle.BINARY -> Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Reduction quests succeed by NOT doing the thing, so use plain, honest
            // labels: "Stayed on track" vs "Slipped" (slipping is rewarded for honesty).
            Button(onClick = onComplete, enabled = enabled) {
                Text(if (quest.isReductionQuest) "Stayed on track" else "Complete")
            }
            OutlinedButton(onClick = onSkip, enabled = enabled) {
                Text(if (quest.isReductionQuest) "Slipped" else "Skip")
            }
        }

        CompletionStyle.QUANTITATIVE -> {
            val target = (quest.targetCount ?: 1).coerceAtLeast(1)
            var count by remember(quest.id, progress) { mutableIntStateOf(progress.coerceIn(0, target)) }
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (count > 0) count-- }, enabled = enabled) { Text("−") }
                    Text("$count / $target ${quest.unit.orEmpty()}".trim(), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { if (count < target) count++ }, enabled = enabled) { Text("+") }
                }
                Button(onClick = { onMeasured(count) }, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Log progress")
                }
            }
        }

        CompletionStyle.DURATION -> {
            val target = quest.estimatedMinutes.coerceAtLeast(1)
            var minutes by remember(quest.id, progress) { mutableIntStateOf(if (progress > 0) progress else target) }
            Column(Modifier.padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (minutes >= 5) minutes -= 5 }, enabled = enabled) { Text("−5") }
                    Text("$minutes / $target min", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { minutes = (minutes + 5).coerceAtMost(1440) }, enabled = enabled) { Text("+5") }
                }
                Button(onClick = { onMeasured(minutes) }, enabled = enabled, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Log time")
                }
            }
        }

        CompletionStyle.SUBJECTIVE -> Column(Modifier.padding(top = 8.dp)) {
            Text("How did it go?", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                (1..5).forEach { rating ->
                    OutlinedButton(onClick = { onMeasured(rating) }, enabled = enabled) { Text("$rating") }
                }
            }
        }
    }
}
