package com.questloop.app.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.CategoryDot
import com.questloop.app.ui.components.DifficultyPips
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.LevelBar
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestInstance
import com.questloop.core.safety.SafetyGuard

/** Callbacks the Today screen needs; grouped so the content is easy to test. */
data class TodayActions(
    val onComplete: (Quest) -> Unit,
    val onSkip: (Quest) -> Unit,
    val onCompleteMeasured: (Quest, Int) -> Unit,
    val onCheckIn: (energy: Int, minutes: Int) -> Unit,
)

/** Stateful entry point: wires the ViewModel to the stateless [TodayContent]. */
@Composable
fun TodayScreen(viewModel: TodayViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (state.pendingUndo != null) "Undo" else null,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLast() else viewModel.consumeToast()
    }

    TodayContent(
        state = state,
        actions = TodayActions(
            onComplete = { viewModel.complete(it, CompletionResult.COMPLETED) },
            onSkip = { viewModel.complete(it, CompletionResult.SKIPPED) },
            onCompleteMeasured = { quest, value -> viewModel.completeMeasured(quest, value) },
            onCheckIn = viewModel::setCheckIn,
        ),
    )
}

/** Pure UI for the Today screen — no ViewModel dependency, fully testable. */
@Composable
fun TodayContent(state: TodayUiState, actions: TodayActions) {
    if (state.loading && state.plan == null) {
        Column(
            Modifier.fillMaxSize().testTag("today-loading"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("today-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LevelBar(
                level = state.level,
                fraction = state.levelProgress,
                totalXp = state.totalXp,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        state.signals.firstOrNull()?.let { signal ->
            item { SafetyBanner(signal = signal, extra = state.signals.size - 1) }
        }

        item { EnergyCheckInRow(selectedEnergy = state.energy, onSelect = actions.onCheckIn) }

        val quests = state.plan?.quests.orEmpty()
        if (quests.isEmpty()) {
            item {
                InfoCard(
                    title = "All clear ✓",
                    body = "Nothing left today. Add a quest, or rest — that counts too.",
                )
            }
        } else {
            items(quests, key = { it.instanceId }) { instance ->
                QuestRow(
                    instance = instance,
                    actions = actions,
                    progress = state.todayProgress[instance.quest.id] ?: 0,
                )
            }
        }

        // Achievements as a compact badge strip rather than stacked cards.
        if (state.achievements.isNotEmpty()) {
            item { AchievementStrip(state.achievements.map { it.title }) }
        }

        item { Spacer(Modifier.padding(24.dp)) }
    }
}

/** One slim banner for the top safety signal (replaces a card per signal). */
@Composable
private fun SafetyBanner(signal: SafetyGuard.Signal, extra: Int) {
    val emoji = when (signal.severity) {
        SafetyGuard.Severity.WARNING -> "⚠️"
        SafetyGuard.Severity.SUGGESTION -> "💡"
        SafetyGuard.Severity.INFO -> "ℹ️"
    }
    val suffix = if (extra > 0) "  (+$extra)" else ""
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            "$emoji ${signal.message}$suffix",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Compact emoji segmented control; no question text. */
@Composable
private fun EnergyCheckInRow(selectedEnergy: Int?, onSelect: (energy: Int, minutes: Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Energy", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        EnergyOption("🔋 Low", 2, 60, selectedEnergy, onSelect)
        EnergyOption("⚡ OK", 3, 120, selectedEnergy, onSelect)
        EnergyOption("🔥 High", 5, 240, selectedEnergy, onSelect)
    }
}

@Composable
private fun EnergyOption(label: String, energy: Int, minutes: Int, selected: Int?, onSelect: (Int, Int) -> Unit) {
    FilterChip(selected = selected == energy, onClick = { onSelect(energy, minutes) }, label = { Text(label) })
}

@Composable
private fun AchievementStrip(titles: List<String>) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        titles.forEach { AssistChip(onClick = {}, label = { Text("🏆 $it") }) }
    }
}

@Composable
private fun QuestRow(instance: QuestInstance, actions: TodayActions, progress: Int) {
    val quest: Quest = instance.quest
    var showWhy by remember(quest.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("quest-${quest.id}")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryDot(quest.category)
                Text(
                    quest.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DifficultyPips(quest.difficulty)
                Text("${quest.estimatedMinutes}m", style = MaterialTheme.typography.bodySmall)
                if (quest.rationale != null) {
                    Text(
                        if (showWhy) "Hide" else "Why?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showWhy = !showWhy },
                    )
                }
            }
            if (showWhy) {
                quest.rationale?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            when (quest.completionStyle) {
                CompletionStyle.BINARY -> BinaryActions(quest, actions)
                CompletionStyle.QUANTITATIVE -> QuantitativeControl(quest, actions, progress)
                CompletionStyle.DURATION -> DurationControl(quest, actions, progress)
                CompletionStyle.SUBJECTIVE -> SubjectiveControl(quest, actions)
            }
        }
    }
}

@Composable
private fun BinaryActions(quest: Quest, actions: TodayActions) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { actions.onComplete(quest) }) { Text("Complete") }
        OutlinedButton(onClick = { actions.onSkip(quest) }) {
            Text(if (quest.isReductionQuest) "Log honestly" else "Skip")
        }
    }
}

@Composable
private fun QuantitativeControl(quest: Quest, actions: TodayActions, progress: Int) {
    val target = (quest.targetCount ?: 1).coerceAtLeast(1)
    var count by remember(quest.id, progress) { mutableIntStateOf(progress.coerceIn(0, target)) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (count > 0) count-- }) { Text("−") }
            Text("$count / $target ${quest.unit.orEmpty()}".trim(), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { if (count < target) count++ }) { Text("+") }
        }
        Button(onClick = { actions.onCompleteMeasured(quest, count) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Log progress")
        }
    }
}

@Composable
private fun DurationControl(quest: Quest, actions: TodayActions, progress: Int) {
    val target = quest.estimatedMinutes.coerceAtLeast(1)
    var minutes by remember(quest.id, progress) { mutableIntStateOf(if (progress > 0) progress else target) }
    Column(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (minutes >= 5) minutes -= 5 }) { Text("−5") }
            Text("$minutes / $target min", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { minutes += 5 }) { Text("+5") }
        }
        Button(onClick = { actions.onCompleteMeasured(quest, minutes) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Log time")
        }
    }
}

@Composable
private fun SubjectiveControl(quest: Quest, actions: TodayActions) {
    Column(Modifier.padding(top = 8.dp)) {
        Text("How did it go?", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            (1..5).forEach { rating ->
                OutlinedButton(onClick = { actions.onCompleteMeasured(quest, rating) }) { Text("$rating") }
            }
        }
    }
}
