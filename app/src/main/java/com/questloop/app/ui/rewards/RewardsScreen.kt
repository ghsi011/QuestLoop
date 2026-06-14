package com.questloop.app.ui.rewards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.InfoCard
import com.questloop.app.ui.components.SectionHeader

@Composable
fun RewardsScreen(viewModel: RewardsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var budgetText by remember(state.budgetCap) { mutableStateOf(if (state.budgetCap > 0) state.budgetCap.toString() else "") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader("Real-world rewards")

        InfoCard(
            title = "You're in control",
            body = "QuestLoop never holds, moves, or invests your money. It only helps you track a " +
                "self-imposed reward budget you manage entirely outside the app.",
        )

        OutlinedTextField(
            value = budgetText,
            onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text("Affordable monthly reward budget") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.setBudgetCap(budgetText.toDoubleOrNull() ?: 0.0) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save budget") }

        state.allowance?.let { allowance ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Suggested allowance this month",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "%.2f".format(allowance.suggestedAllowance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    Text(allowance.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionHeader("Please remember")
            allowance.disclaimers.forEach { disclaimer ->
                Text("• $disclaimer", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionHeader("Setting up your fund")
        InfoCard(
            title = "Admin quests",
            body = "1. Open a separate savings pot or account you control.\n" +
                "2. Pick an affordable monthly contribution.\n" +
                "3. Set up a recurring transfer outside QuestLoop.\n" +
                "4. Each month, release only what you've earned — and only what you can afford.",
        )
    }
}
