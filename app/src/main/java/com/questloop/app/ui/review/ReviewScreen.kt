package com.questloop.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.review.ReviewGenerator
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(viewModel: ReviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Reviews") }
        state.weekly?.let { item { ReviewCard(it) } }
        state.monthly?.let { item { ReviewCard(it) } }
        if (state.weekly == null && state.monthly == null && !state.loading) {
            item { Text("No activity yet — complete a few quests to see your review.") }
        }
        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun ReviewCard(review: ReviewGenerator.Review) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(review.periodLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "${review.totalCompleted}/${review.totalAttempted} completed · ${review.activeDays} active days · +${review.xpEarned} XP",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            LinearProgressIndicator(
                progress = { review.completionRate.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            if (review.byCategory.isNotEmpty()) {
                Text("By category", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                review.byCategory.forEach { stat ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stat.category.pretty(), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${stat.completed}/${stat.attempted} (${(stat.completionRate * 100).roundToInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (review.highlights.isNotEmpty()) {
                Text("Highlights", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                review.highlights.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (review.suggestions.isNotEmpty()) {
                Text("Suggestions", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                review.suggestions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
