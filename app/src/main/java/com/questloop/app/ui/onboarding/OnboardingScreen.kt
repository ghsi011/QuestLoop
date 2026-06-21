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
 * and surfaces the local-first privacy stance up front, before the user touches
 * anything sensitive (SPEC §6, §9). The money/rewards disclaimer lives on the
 * Rewards tab.
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
            "Turn todos, habits, and goals into quests. Earn XP and streaks — fair and forgiving.",
            style = MaterialTheme.typography.bodyLarge,
        )

        InfoCard(
            title = "🎯 A gentle daily loop",
            body = "A quick morning glance, a short evening wrap-up. Rough days are okay.",
        )
        InfoCard(
            title = "🔒 Private by default",
            body = "Everything stays on-device. Export or delete anytime in Settings.",
        )
        InfoCard(
            title = "✨ Optional AI",
            body = "Add a key in Settings to turn lists into quests.",
        )

        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        ) { Text("Get started") }
    }
}
