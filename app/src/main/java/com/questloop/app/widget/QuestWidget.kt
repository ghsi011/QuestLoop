package com.questloop.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import com.questloop.app.QuestLoopApplication
import com.questloop.core.model.DayPart
import java.time.LocalDate
import java.time.LocalTime

/** A task shown on the widget: enough to render a row and open its completion menu. */
private data class WidgetTask(val id: String, val title: String)

/**
 * Home-screen widget (SPEC §3 minimal interaction). Shows the "Add a quest" box plus
 * every daily/one-off task still due today, in a scrollable list. Every interaction —
 * adding a quest, ticking one off — happens in a lightweight dialog over the home
 * screen; the widget never opens the app. Data is read directly from Room on update.
 */
class QuestWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val epochDay = LocalDate.now().toEpochDay()
        val tasks = runCatching {
            // Reuse the app's single wired repository rather than hand-wiring one.
            val repo = (context.applicationContext as QuestLoopApplication).container.repository
            repo.widgetQuickTasks(epochDay, DayPart.fromHour(LocalTime.now().hour))
                .map { WidgetTask(it.id, it.title) }
        }.getOrDefault(emptyList())

        provideContent { WidgetBody(tasks, epochDay) }
    }
}

@Composable
private fun WidgetBody(tasks: List<WidgetTask>, epochDay: Long) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp),
        ) {
            // Quick-add field. Widgets can't host an editable input, so this is a
            // tappable box that opens AddQuestActivity over the home screen.
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
                if (tasks.isEmpty()) "All clear today ✓" else "${tasks.size} to do",
                style = TextStyle(color = GlanceTheme.colors.onBackground),
            )
            Spacer(GlanceModifier.height(4.dp))
            // Every due daily/one-off task, scrollable so they all fit. Tapping a row
            // opens its completion menu (also over the home screen).
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id.hashCode().toLong() }) { task ->
                    TaskRow(task, epochDay)
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: WidgetTask, epochDay: Long) {
    val context = LocalContext.current
    // Distinct data per row so each row's PendingIntent is unique — PendingIntent
    // equality ignores extras, so without this all rows would share one intent.
    val intent = Intent(context, CompleteQuestActivity::class.java)
        .setData(Uri.parse("questloop://complete/${task.id}"))
        .putExtra(CompleteQuestActivity.EXTRA_QUEST_ID, task.id)
        .putExtra(CompleteQuestActivity.EXTRA_EPOCH_DAY, epochDay)
    Text(
        "○  ${task.title}",
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(actionStartActivity(intent)),
    )
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
