package com.questloop.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.questloop.app.MainActivity
import com.questloop.app.data.ProfileStore
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.DayPart
import java.time.LocalDate
import java.time.LocalTime

/**
 * Home-screen widget showing today's top quests at a glance (SPEC §3 minimal
 * interaction). Tapping it opens the app. Data is read directly from Room when
 * the widget updates.
 */
class QuestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val titles = runCatching {
            val db = QuestLoopDatabase.get(context)
            val repo = QuestRepository(db.questDao(), db.completionDao(), ProfileStore(context.applicationContext))
            val plan = repo.todayPlan(
                LocalDate.now().toEpochDay(),
                DayPart.fromHour(LocalTime.now().hour),
            )
            plan.quests.map { it.quest.title }
        }.getOrDefault(emptyList())

        provideContent { WidgetBody(titles) }
    }
}

@Composable
private fun WidgetBody(titles: List<String>) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                if (titles.isEmpty()) "QuestLoop — all clear ✓" else "QuestLoop — ${titles.size} today",
                style = TextStyle(color = GlanceTheme.colors.onBackground),
            )
            titles.take(3).forEach { title ->
                Text("• $title", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            }
        }
    }
}

class QuestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuestWidget()
}
