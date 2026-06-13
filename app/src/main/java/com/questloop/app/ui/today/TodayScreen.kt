package com.questloop.app.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.CategoryChip
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.LevelBar
import com.questloop.app.ui.components.SectionHeader
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestInstance

@Composable
fun TodayScreen(viewModel: TodayViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    if (state.loading && state.plan == null) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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

        if (state.signals.isNotEmpty()) {
            items(state.signals) { signal ->
                InfoCard(title = signal.severity.name.lowercase().replaceFirstChar { it.uppercase() }, body = signal.message)
            }
        }

        item { EnergyCheckInRow(onSelect = viewModel::setCheckIn) }

        item { SectionHeader("Today's quests") }

        val quests = state.plan?.quests.orEmpty()
        if (quests.isEmpty()) {
            item {
                InfoCard(
                    title = "All clear",
                    body = "No quests for today. Add one, or enjoy a well-earned rest day.",
                )
            }
        } else {
            items(quests, key = { it.instanceId }) { instance ->
                QuestRow(
                    instance = instance,
                    onComplete = { viewModel.complete(instance.quest, CompletionResult.COMPLETED) },
                    onSkip = { viewModel.complete(instance.quest, CompletionResult.SKIPPED) },
                )
            }
        }

        state.plan?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
            item { SectionHeader("Notes") }
            items(notes) { note -> Text("• $note", style = MaterialTheme.typography.bodySmall) }
        }

        if (state.achievements.isNotEmpty()) {
            item { SectionHeader("Achievements (${state.achievements.size})") }
            items(state.achievements) { achievement ->
                InfoCard(title = "🏆 ${achievement.title}", body = achievement.description)
            }
        }

        val deferred = state.plan?.deferred.orEmpty()
        if (deferred.isNotEmpty()) {
            item { SectionHeader("Deferred (${deferred.size})") }
            items(deferred) { quest ->
                Text("• ${quest.title}", style = MaterialTheme.typography.bodySmall)
            }
        }

        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun EnergyCheckInRow(onSelect: (energy: Int, minutes: Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("How's your energy today?", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                EnergyOption("Low", 2, 60, onSelect)
                EnergyOption("Medium", 3, 120, onSelect)
                EnergyOption("High", 5, 240, onSelect)
            }
        }
    }
}

@Composable
private fun EnergyOption(label: String, energy: Int, minutes: Int, onSelect: (Int, Int) -> Unit) {
    val selected = remember { false }
    FilterChip(selected = selected, onClick = { onSelect(energy, minutes) }, label = { Text(label) })
}

@Composable
private fun QuestRow(instance: QuestInstance, onComplete: () -> Unit, onSkip: () -> Unit) {
    val quest: Quest = instance.quest
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(quest.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryChip(quest.category)
                Text(
                    "${quest.difficulty.name.lowercase()} · ${quest.estimatedMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            quest.rationale?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onComplete) { Text("Complete") }
                OutlinedButton(onClick = onSkip) {
                    Text(if (quest.isReductionQuest) "Log honestly" else "Skip")
                }
            }
        }
    }
}
