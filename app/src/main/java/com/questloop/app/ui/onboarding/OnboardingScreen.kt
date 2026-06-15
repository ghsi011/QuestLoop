package com.questloop.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.questloop.app.ui.components.InfoCard

/**
 * One-time intro shown on first launch. Sets expectations for the quest/XP model
 * and — importantly — surfaces the money/rewards disclaimer and the local-first
 * privacy stance up front, before the user touches anything sensitive (SPEC §6, §9).
 */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Welcome to QuestLoop",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "Turn your real-life todos, habits, and goals into quests. Complete them to earn " +
                "XP, levels, and streaks — designed to feel fair and forgiving, never punishing.",
            style = MaterialTheme.typography.bodyLarge,
        )

        InfoCard(
            title = "🎯 A gentle daily loop",
            body = "A quick morning glance and a short evening wrap-up are all it takes. Tough days " +
                "are okay — recovery and consistency matter more than perfection.",
        )
        InfoCard(
            title = "💸 Your rewards stay yours",
            body = "QuestLoop never holds, moves, or invests your money, and gives no financial " +
                "advice. It only helps you track a reward budget you set and manage yourself, " +
                "outside the app. Only set aside what you can comfortably afford.",
        )
        InfoCard(
            title = "🔒 Private by default",
            body = "Everything stays on this device — nothing is uploaded, and backups are off " +
                "unless you turn them on. You can export or delete all your data anytime in Settings.",
        )
        InfoCard(
            title = "✨ Optional AI",
            body = "If you want, add your own AI key in Settings to turn messy todo lists into " +
                "quests. It's entirely optional — everything works without it.",
        )

        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        ) { Text("Get started") }
    }
}
