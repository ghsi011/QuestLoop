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
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.CategoryDot
import com.questloop.app.ui.components.DifficultyPips
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.LevelRing
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestInstance
import com.questloop.core.safety.SafetyGuard

/** Callbacks the Today screen needs; grouped so the content is easy to test. */
data class TodayActions(
    val onComplete: (Quest) -> Unit,
    val onSkip: (Quest) -> Unit,
    val onCompleteMeasured: (Quest, Int) -> Unit,
    val onCheckIn: (energy: Int) -> Unit,
    /** Tapping the already-selected energy chip clears the check-in. */
    val onClearCheckIn: () -> Unit = {},
    val onOpenQuestBank: () -> Unit = {},
    val onOpenAddQuest: () -> Unit = {},
    /** Permanently dismiss a first-run guide quest. */
    val onDismissGuide: (Quest) -> Unit = {},
)

/** Stateful entry point: wires the ViewModel to the stateless [TodayContent]. */
@Composable
fun TodayScreen(
    viewModel: TodayViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenAchievements: () -> Unit = {},
    onOpenQuestBank: () -> Unit = {},
    onOpenAddQuest: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Refresh when re-entering Today so XP/level/plan reflect changes made on other
    // tabs (e.g. completing a quest from the Quests backlog).
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.toastId) {
        val message = state.toast ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (state.pendingUndo != null) "Undo" else null,
            // Keep undoable messages up longer — ~4s is too short to read the
            // outcome and still reverse a mis-tap.
            duration = if (state.pendingUndo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoLast() else viewModel.consumeToast()
    }

    fun buzz() = haptics.performHapticFeedback(HapticFeedbackType.LongPress)

    TodayContent(
        state = state,
        actions = TodayActions(
            onComplete = { buzz(); viewModel.complete(it, CompletionResult.COMPLETED) },
            onSkip = { viewModel.complete(it, CompletionResult.SKIPPED) },
            onCompleteMeasured = { quest, value -> buzz(); viewModel.completeMeasured(quest, value) },
            onCheckIn = viewModel::setCheckIn,
            onClearCheckIn = viewModel::clearCheckIn,
            onOpenQuestBank = onOpenQuestBank,
            onOpenAddQuest = onOpenAddQuest,
            onDismissGuide = viewModel::dismissGuide,
        ),
        onOpenAchievements = onOpenAchievements,
    )
}

/** Pure UI for the Today screen — no ViewModel dependency, fully testable. */
@Composable
fun TodayContent(state: TodayUiState, actions: TodayActions, onOpenAchievements: () -> Unit = {}) {
    if (state.loading && state.plan == null) {
        Column(
            Modifier.fillMaxSize().testTag("today-loading"),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { CircularProgressIndicator() }
        return
    }

    var focusMode by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("today-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            LevelRing(
                level = state.level,
                fraction = state.levelProgress,
                totalXp = state.totalXp,
                streakDays = state.streak,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        if (state.signals.isNotEmpty()) {
            item { SafetyBanner(state.signals) }
        }

        item {
            EnergyCheckInRow(
                selectedEnergy = state.energy,
                onSelect = actions.onCheckIn,
                onClear = actions.onClearCheckIn,
            )
        }

        val quests = state.plan?.quests.orEmpty()
        if (quests.isNotEmpty() && state.availableMinutes > 0) {
            item { EnergyBudgetBar(plannedMinutes = state.plannedMinutes, availableMinutes = state.availableMinutes) }
        }
        if (quests.isNotEmpty()) {
            state.planRationale?.let { rationale ->
                item {
                    Text(
                        rationale,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (quests.isEmpty()) {
            item {
                InfoCard(
                    title = "All clear ✓",
                    body = "Nothing left today. Add a quest, or rest — that counts too.",
                )
            }
        } else {
            if (quests.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (focusMode) "Next up" else "Today",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = focusMode,
                            onClick = { focusMode = !focusMode },
                            label = { Text("Focus") },
                        )
                    }
                }
            }
            val shown = if (focusMode) quests.take(1) else quests
            items(shown, key = { it.instanceId }) { instance ->
                QuestRow(
                    instance = instance,
                    actions = actions,
                    progress = state.todayProgress[instance.quest.id] ?: 0,
                    enabled = !state.completing,
                )
            }
            if (focusMode && quests.size > 1) {
                item {
                    Text(
                        "+${quests.size - 1} more — turn off Focus to see all",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Achievements as a compact badge strip rather than stacked cards.
        if (state.achievements.isNotEmpty()) {
            item { AchievementStrip(state.achievements.map { it.title }, onOpenAchievements) }
        }

        item { Spacer(Modifier.padding(24.dp)) }
    }
}

/**
 * The day's time budget: estimated minutes planned vs. available (from the energy
 * check-in, or the default). Makes the energy-budgeting that already shapes the
 * plan visible — over budget is stated plainly, never as a scolding.
 */
@Composable
private fun EnergyBudgetBar(plannedMinutes: Int, availableMinutes: Int) {
    val fraction = if (availableMinutes <= 0) 0f else (plannedMinutes.toFloat() / availableMinutes).coerceIn(0f, 1f)
    val over = availableMinutes in 1 until plannedMinutes
    val accent = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val spoken = "Time planned: about $plannedMinutes of $availableMinutes minutes" +
        if (over) ", more than today's time." else "."
    Column(
        // One spoken summary instead of three disconnected fragments + a raw percentage.
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Time planned", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "≈ $plannedMinutes / $availableMinutes min",
                style = MaterialTheme.typography.bodySmall,
                color = if (over) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = accent,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (over) {
            Text(
                "More than today's time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** One slim banner for the top safety signal (replaces a card per signal). */
@Composable
private fun SafetyBanner(signals: List<SafetyGuard.Signal>) {
    if (signals.isEmpty()) return
    // Reset expand/dismiss when the signals themselves change.
    val key = signals.joinToString("|") { it.message }
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var dismissed by rememberSaveable(key) { mutableStateOf(false) }
    if (dismissed) return

    fun emoji(s: SafetyGuard.Signal) = when (s.severity) {
        SafetyGuard.Severity.WARNING -> "⚠️"
        SafetyGuard.Severity.SUGGESTION -> "💡"
        SafetyGuard.Severity.INFO -> "ℹ️"
    }
    val shown = if (expanded) signals else signals.take(1)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            shown.forEach { s ->
                Text("${emoji(s)} ${s.message}", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (signals.size > 1) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show less" else "Show ${signals.size - 1} more")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { dismissed = true }) { Text("Dismiss") }
            }
        }
    }
}

/** Compact emoji segmented control; no question text. A "Rest" option (energy 1)
 *  is distinct from "Low" — it schedules only the gentle daily routines. Re-tapping
 *  the selected chip clears the check-in. */
@Composable
private fun EnergyCheckInRow(
    selectedEnergy: Int?,
    onSelect: (energy: Int) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        // FilterChips already expose their own selected (toggle) state to
        // accessibility services, so no selectableGroup() (which models
        // single-choice radio semantics and would mismatch here).
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Energy", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        EnergyOption("😴 Rest", 1, selectedEnergy, onSelect, onClear)
        EnergyOption("🔋 Low", 2, selectedEnergy, onSelect, onClear)
        EnergyOption("⚡ OK", 3, selectedEnergy, onSelect, onClear)
        EnergyOption("🔥 High", 5, selectedEnergy, onSelect, onClear)
    }
}

@Composable
private fun EnergyOption(
    label: String,
    energy: Int,
    selected: Int?,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val isSelected = selected == energy
    FilterChip(
        selected = isSelected,
        // Re-tapping the active chip deselects it; otherwise select this level.
        onClick = { if (isSelected) onClear() else onSelect(energy) },
        label = { Text(label) },
    )
}

@Composable
private fun AchievementStrip(titles: List<String>, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        titles.forEach { AssistChip(onClick = onOpen, label = { Text("🏆 $it") }) }
        AssistChip(onClick = onOpen, label = { Text("See all") })
    }
}

@Composable
private fun QuestRow(instance: QuestInstance, actions: TodayActions, progress: Int, enabled: Boolean = true) {
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
                        // Reserve a 48dp touch target so this small label meets the
                        // Material accessibility minimum.
                        modifier = Modifier
                            .clickable { showWhy = !showWhy }
                            .minimumInteractiveComponentSize(),
                    )
                }
            }
            if (showWhy) {
                quest.rationale?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }

            when (quest.id) {
                com.questloop.app.data.SampleData.ONBOARDING_PICK ->
                    OnboardingActions("Browse quest bank", actions.onOpenQuestBank) { actions.onDismissGuide(quest) }
                com.questloop.app.data.SampleData.ONBOARDING_CREATE ->
                    OnboardingActions("Create a quest", actions.onOpenAddQuest) { actions.onDismissGuide(quest) }
                else -> com.questloop.app.ui.components.QuestCompletionControls(
                    quest = quest,
                    progress = progress,
                    onComplete = { actions.onComplete(quest) },
                    onSkip = { actions.onSkip(quest) },
                    onMeasured = { actions.onCompleteMeasured(quest, it) },
                    enabled = enabled,
                )
            }
        }
    }
}

/** First-run guide quests: a primary CTA that navigates, plus a quiet dismiss. */
@Composable
private fun OnboardingActions(ctaLabel: String, onCta: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onCta) { Text(ctaLabel) }
        OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
