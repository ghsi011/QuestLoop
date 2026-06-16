package com.questloop.app.ui.quests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.CategoryDot
import com.questloop.app.ui.components.DifficultyPips
import com.questloop.core.model.Quest

@Composable
fun QuestBankScreen(viewModel: QuestBankViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.toastId) {
        val message = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        viewModel.consumeToast()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Add ready-made quests to your list. You can edit or remove them anytime.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
        }
        state.groups.forEach { group ->
            item(key = "h-${group.title}") {
                Text(
                    group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(group.items, key = { it.id }) { quest ->
                BankRow(
                    quest = quest,
                    added = quest.id in state.addedIds,
                    onAdd = { viewModel.add(quest) },
                )
            }
        }
        item { Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun BankRow(quest: Quest, added: Boolean, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryDot(quest.category)
            Column(Modifier.weight(1f)) {
                Text(quest.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DifficultyPips(quest.difficulty)
                    Text("${quest.estimatedMinutes}m", style = MaterialTheme.typography.bodySmall)
                }
                quest.rationale?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (added) {
                OutlinedButton(onClick = {}, enabled = false) { Text("Added") }
            } else {
                Button(onClick = onAdd) { Text("Add") }
            }
        }
    }
}
