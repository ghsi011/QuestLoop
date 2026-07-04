package com.questloop.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

/** Compact hero: a level ring (progress to next level) + XP and streak flame. */
@Composable
fun LevelRing(
    level: Int,
    fraction: Double,
    totalXp: Long,
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    val ringColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(64.dp)) {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 8.dp.toPx())
                    drawArc(trackColor, 0f, 360f, false, style = stroke)
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = (fraction.coerceIn(0.0, 1.0) * 360f).toFloat(),
                        useCenter = false,
                        style = stroke,
                    )
                }
                Text("$level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("Level $level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("$totalXp XP", style = MaterialTheme.typography.bodyMedium)
                if (streakDays > 0) {
                    Text("🔥 $streakDays-day streak", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, body: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

fun QuestCategory.pretty(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** A stable, distinct accent colour per category — glanceable without text. */
fun QuestCategory.color(): Color = when (this) {
    QuestCategory.HEALTH -> Color(0xFF10B981)
    QuestCategory.LIFE_ADMIN -> Color(0xFF6366F1)
    QuestCategory.CHORES -> Color(0xFFF59E0B)
    QuestCategory.WORK_STUDY -> Color(0xFF3B82F6)
    QuestCategory.SOCIAL -> Color(0xFFEC4899)
    QuestCategory.PERSONAL_GROWTH -> Color(0xFF8B5CF6)
    QuestCategory.HOBBY_MAINTENANCE -> Color(0xFF14B8A6)
    QuestCategory.BAD_HABIT_REDUCTION -> Color(0xFFEF4444)
    QuestCategory.META_MAINTENANCE -> Color(0xFF64748B)
}

/** Small colored dot used as a category marker. Colour alone isn't accessible,
 *  so it carries the category name as a content description for screen readers. */
@Composable
fun CategoryDot(category: QuestCategory, modifier: Modifier = Modifier) {
    val label = category.pretty()
    androidx.compose.foundation.layout.Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(category.color())
            .semantics { contentDescription = label },
    )
}

/** Pretty, human label for a difficulty (e.g. "Medium"). */
fun Difficulty.pretty(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Pretty, human label for a recurrence cadence (e.g. "One-off"). */
fun com.questloop.core.model.QuestFrequency.pretty(): String = when (this) {
    com.questloop.core.model.QuestFrequency.DAILY -> "Daily"
    com.questloop.core.model.QuestFrequency.WEEKLY -> "Weekly"
    com.questloop.core.model.QuestFrequency.MONTHLY -> "Monthly"
    com.questloop.core.model.QuestFrequency.RECURRING -> "Recurring"
    com.questloop.core.model.QuestFrequency.ONE_OFF -> "One-off"
    com.questloop.core.model.QuestFrequency.SEASONAL -> "Seasonal"
}

/** Plain-words label for how a quest is completed — never the enum name
 *  (docs/CONTENT_STYLE.md: no developer language). */
fun CompletionStyle.pretty(): String = when (this) {
    CompletionStyle.BINARY -> "Done or not"
    CompletionStyle.QUANTITATIVE -> "Count"
    CompletionStyle.DURATION -> "Time"
    CompletionStyle.SUBJECTIVE -> "Rate 1-5"
}

/** Categories offered in the add/edit pickers. Meta maintenance is the app's own
 *  upkeep bucket (XP-capped), so it isn't offered — existing quests keep it. */
val pickableCategories: List<QuestCategory> = QuestCategory.entries.filterNot { it.isMeta }

/** Cadences offered in the add/edit pickers. RECURRING schedules exactly like
 *  DAILY and SEASONAL is internal, so neither is offered — both stay valid on
 *  existing quests and AI suggestions. */
val pickableFrequencies: List<QuestFrequency> = listOf(
    QuestFrequency.DAILY,
    QuestFrequency.WEEKLY,
    QuestFrequency.MONTHLY,
    QuestFrequency.ONE_OFF,
)

/** Difficulty shown as filled pips (e.g. ●●●○○) instead of a word. The pip count
 *  is visual-only, so the tier is exposed as a content description for TalkBack. */
@Composable
fun DifficultyPips(difficulty: Difficulty, modifier: Modifier = Modifier) {
    val filled = difficulty.ordinal + 1
    val label = "${difficulty.pretty()} difficulty"
    Row(
        modifier.semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(Difficulty.entries.size) { i ->
            val on = i < filled
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
            )
        }
    }
}
