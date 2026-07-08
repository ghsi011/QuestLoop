package com.questloop.app.ui.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.questloop.app.ui.Money
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.OneShotSnackbarEffect
import com.questloop.app.ui.components.SectionHeader
import com.questloop.core.model.Quest
import com.questloop.core.reward.RewardAllowanceCalculator
import java.text.DecimalFormatSymbols

@Composable
fun RewardsScreen(viewModel: RewardsViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var budgetText by remember(state.budgetCap) { mutableStateOf(if (state.budgetCap > 0) state.budgetCap.toString() else "") }
    var showDetails by remember { mutableStateOf(false) }

    // Refresh on re-entry so the allowance reflects quests completed elsewhere.
    LaunchedEffect(Unit) { viewModel.load() }
    OneShotSnackbarEffect(
        hostState = snackbarHostState,
        messageId = state.messageId,
        message = state.savedMessage,
        consume = viewModel::consumeSavedMessage,
    )

    val parsedBudget = budgetText.toDoubleOrNull()
    val budgetValid = budgetText.isBlank() || parsedBudget != null

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Rewards")

        state.allowance?.let { allowance ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Earned this month", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        Money.format(allowance.suggestedAllowance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(allowance.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        FundCard(
            budgetSet = state.fundBudgetSet,
            potOpened = state.fundPotOpened,
            steps = state.fundSteps,
            stepInFlight = state.fundStepInFlight,
            onStepDone = viewModel::markFundStepDone,
        )

        OutlinedTextField(
            value = budgetText,
            onValueChange = { input ->
                // Digits and at most one decimal point. The device locale decides what
                // a comma means: in comma-decimal locales (where KeyboardType.Decimal
                // offers a comma key) it's the decimal point — dropping it would turn
                // 12,50 into 1250 — while in dot-decimal locales it's a grouping
                // separator — treating it as a point would turn 1,500 into 1.5.
                val commaIsDecimal = DecimalFormatSymbols.getInstance().decimalSeparator == ','
                val filtered = if (commaIsDecimal) {
                    input.filter { c -> c.isDigit() || c == ',' }.replace(',', '.')
                } else {
                    input.filter { c -> c.isDigit() || c == '.' }
                }
                val firstDot = filtered.indexOf('.')
                budgetText = if (firstDot < 0) filtered
                else filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
            },
            label = { Text("Affordable monthly budget") },
            isError = !budgetValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.setBudgetCap(parsedBudget ?: 0.0) },
            enabled = budgetValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save budget")
        }

        Text(
            "You manage your own money. Not financial advice.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "Hide details" else "How rewards work")
        }
        if (showDetails) {
            RewardAllowanceCalculator.DISCLAIMERS.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall)
            }
            InfoCard(
                title = "Set up your own fund",
                body = "1. Open a savings pot you control.\n2. Pick an affordable monthly amount.\n" +
                    "3. Auto-transfer it outside QuestLoop.\n4. Release only what you've earned and can afford.",
            )
        }
    }
}

/**
 * Live reward-fund status (SPEC §6): the setup milestones already reached, plus
 * the admin steps actionable right now (open a pot / fund this month / claim),
 * each completable here. Replaces the old static "set up your fund" checklist
 * with state that reflects where the user actually is.
 */
@Composable
private fun FundCard(
    budgetSet: Boolean,
    potOpened: Boolean,
    steps: List<Quest>,
    stepInFlight: Boolean,
    onStepDone: (Quest) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your reward fund", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (!budgetSet) {
                Text(
                    "Set an affordable monthly budget below to start your fund.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatusRow(done = true, label = "Budget set")
                if (potOpened) StatusRow(done = true, label = "External pot opened")
                steps.forEach { step ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        TextButton(
                            onClick = { onStepDone(step) },
                            enabled = !stepInFlight,
                        ) { Text("Mark done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(done: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (done) "✓" else "○", style = MaterialTheme.typography.bodyMedium)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
