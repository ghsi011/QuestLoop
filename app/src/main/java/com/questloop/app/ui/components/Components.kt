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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.questloop.core.model.Difficulty
import com.questloop.core.model.QuestCategory

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun LevelBar(level: Int, fraction: Double, totalXp: Long, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Level $level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("$totalXp XP", style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun CategoryChip(category: QuestCategory) {
    AssistChip(
        onClick = {},
        label = { Text(category.pretty()) },
    )
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
    QuestCategory.BAD_HABIT_REDUCTION -> Color(0xFFEF4444)
    QuestCategory.META_MAINTENANCE -> Color(0xFF64748B)
}

/** Small colored dot used as a category marker. */
@Composable
fun CategoryDot(category: QuestCategory, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier.size(10.dp).clip(CircleShape).background(category.color()),
    )
}

/** Difficulty shown as filled pips (e.g. ●●●○○) instead of a word. */
@Composable
fun DifficultyPips(difficulty: Difficulty, modifier: Modifier = Modifier) {
    val filled = difficulty.ordinal + 1
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
