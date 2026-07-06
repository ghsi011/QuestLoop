package com.questloop.app.widget

import android.appwidget.AppWidgetManager
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.questloop.app.MainActivity
import com.questloop.app.QuestLoopApplication
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
            // Reuse the app's single wired repository rather than hand-wiring one.
            val repo = (context.applicationContext as QuestLoopApplication).container.repository
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
                // Tapping the widget surface (anywhere outside the add box below)
                // opens the app; the add box's own click takes precedence over this.
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            // Quick-add field. Widgets can't host an editable input, so this is a
            // tappable box that opens AddQuestActivity; what's typed there goes
            // straight to AI generation and is saved as a one-off quest (no review).
            Text(
                "＋  Add a quest…",
                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer),
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(GlanceTheme.colors.secondaryContainer)
                    .cornerRadius(8.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clickable(actionStartActivity<AddQuestActivity>()),
            )
            Spacer(GlanceModifier.height(8.dp))
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

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // A widget was (re)added: make sure the day-boundary refresh alarm is armed.
        runCatching { WidgetRefreshScheduler(context).scheduleNext() }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget removed: scheduleNext() sees no instances and drops the alarm.
        runCatching { WidgetRefreshScheduler(context).scheduleNext() }
    }
}
