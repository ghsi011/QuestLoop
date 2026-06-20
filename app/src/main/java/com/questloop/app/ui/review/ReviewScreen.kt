package com.questloop.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.questloop.app.ui.components.SectionHeader
import com.questloop.app.ui.components.pretty
import com.questloop.core.generation.PeriodPlanner
import com.questloop.core.review.ReviewGenerator
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Refresh on re-entry so stats reflect quests completed elsewhere.
    LaunchedEffect(Unit) { viewModel.load() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("Reviews") }
        item {
            // Toggle between the retrospective (Review) and the forward plan (Plan).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.mode == ReviewMode.REVIEW,
                    onClick = { viewModel.setMode(ReviewMode.REVIEW) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Review") }
                SegmentedButton(
                    selected = state.mode == ReviewMode.PLAN,
                    onClick = { viewModel.setMode(ReviewMode.PLAN) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Plan") }
            }
        }

        when (state.mode) {
            ReviewMode.REVIEW -> reviewContent(state, viewModel)
            ReviewMode.PLAN -> planContent(state)
        }

        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp)) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.reviewContent(
    state: ReviewUiState,
    viewModel: ReviewViewModel,
) {
    // AI summaries are opt-in: only offered when a key is set, and only on tap.
    if (state.aiAvailable && (state.weekly != null || state.monthly != null)) {
        item {
            OutlinedButton(
                onClick = { viewModel.summarizeWithAi() },
                enabled = !state.summarizing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.summarizing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Summarize with AI ✨")
                }
            }
        }
    }
    state.weekly?.let { item { ReviewCard(it, state.weeklySummary) } }
    state.monthly?.let { item { ReviewCard(it, state.monthlySummary) } }
    if (state.weekly == null && state.monthly == null && !state.loading) {
        item { Text("No activity yet — complete a few quests to see your review.") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.planContent(state: ReviewUiState) {
    state.weeklyPlan?.let { item { PlanCard(it) } }
    state.monthlyPlan?.let { item { PlanCard(it) } }
    if (state.weeklyPlan == null && state.monthlyPlan == null && !state.loading) {
        item { Text("Nothing scheduled yet — add some quests or habits to plan ahead.") }
    }
}

@Composable
private fun ReviewCard(review: ReviewGenerator.Review, summary: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(review.periodLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            // A zero-activity period would otherwise show a bleak "0/0 · 0 days · +0 XP"
            // row and an empty bar; the summary line alone is enough.
            if (review.totalAttempted > 0) {
                Text(
                    "${review.totalCompleted}/${review.totalAttempted} completed · ${review.activeDays} active days · +${review.xpEarned} XP",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
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
}

private val planDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

@Composable
private fun PlanCard(plan: PeriodPlanner.PeriodPlan) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(plan.periodLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (plan.totalEstimatedMinutes > 0) {
                Text(
                    "${plan.items.size} quest(s) · ~${plan.totalEstimatedMinutes} min planned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            plan.notes.forEach {
                Text(
                    "• $it",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            plan.byCategory.forEach { group ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CategoryDot(group.category)
                    Text(
                        group.category.pretty(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                group.items.forEach { PlanItemRow(it) }
            }
        }
    }
}

@Composable
private fun PlanItemRow(item: PeriodPlanner.PlanItem) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.quest.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            planItemTrailing(item),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                item.isOverdue -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Trailing label: deadline pressure when dated, otherwise the cadence + count. */
private fun planItemTrailing(item: PeriodPlanner.PlanItem): String = when {
    item.isOverdue -> "Overdue"
    item.dueThisPeriod && item.deadlineEpochDay != null ->
        "Due ${LocalDate.ofEpochDay(item.deadlineEpochDay!!).format(planDateFormat)}"
    item.expectedOccurrences > 1 -> "${item.quest.frequency.pretty()} ·${item.expectedOccurrences}×"
    else -> item.quest.frequency.pretty()
}
