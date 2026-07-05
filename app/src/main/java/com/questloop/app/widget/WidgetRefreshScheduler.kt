package com.questloop.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.questloop.app.reminders.ReminderSchedule
import com.questloop.core.model.DayPart
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pure time math for the widget's day-boundary refresh: [QuestWidget] snapshots
 * "today" and the current [DayPart] when it renders, so the rendered plan goes
 * stale at midnight (new epoch day) and at each day-part change. Kept separate
 * from Android so it's unit-testable (mirrors [ReminderSchedule]).
 */
object WidgetRefreshSchedule {
    /**
     * Midnight plus every hour where [DayPart.fromHour] changes (currently 12:00
     * and 17:00) — derived from the enum so a boundary tweak in :core can't
     * silently drift out of sync with the widget's refresh times.
     */
    private val boundaryHours: List<Int> =
        listOf(0) + (1..23).filter { DayPart.fromHour(it) != DayPart.fromHour(it - 1) }

    /** The next epoch-millis at which the widget's rendered day / day part goes stale. */
    fun nextBoundaryMillis(nowEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        boundaryHours.minOf { hour -> ReminderSchedule.nextTriggerMillis(nowEpochMillis, hour, 0, zone) }
}

/**
 * Keeps the widget's rendered day current. The data-driven refresh in
 * QuestLoopApplication only fires when something is written, so without this the
 * morning glance — the widget's core use case — would keep showing yesterday's
 * evening plan until the app is opened. Same self-healing pattern as
 * ReminderScheduler: a one-shot inexact-while-idle alarm that re-arms on each
 * fire, with app start and widget add/remove as safety nets. It is armed only
 * while at least one widget instance exists and dropped once the last one goes.
 */
class WidgetRefreshScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** Arm the next day/day-part boundary, or drop the alarm when no widget is placed. */
    fun scheduleNext() {
        if (!hasWidgets()) {
            alarmManager?.cancel(pendingIntent())
            return
        }
        // Unlike ReminderScheduler, no re-arm margin: alarms never fire before
        // their trigger and the strictly-after math already rolls past "now", a
        // duplicate render is harmless, and a margin would skip a boundary that
        // gets armed within a minute of it.
        val triggerAt = WidgetRefreshSchedule.nextBoundaryMillis(System.currentTimeMillis())
        alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent())
    }

    private fun hasWidgets(): Boolean =
        AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, QuestWidgetReceiver::class.java))
            ?.isNotEmpty() == true

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, WidgetRefreshReceiver::class.java).setAction(ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_REFRESH = "com.questloop.app.WIDGET_REFRESH"

        /** Distinct from the reminder request codes (1001/1002 and 1501/1502). */
        private const val REQUEST_CODE = 1101
    }
}

/**
 * Receives the boundary alarm: re-renders the widget so it shows the new day /
 * day part, and re-arms the next boundary so the series can't silently end.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetRefreshScheduler.ACTION_REFRESH) return
        // Self-heal first (mirrors ReminderReceiver): even if the render fails,
        // the next boundary stays armed.
        runCatching { WidgetRefreshScheduler(context).scheduleNext() }
        val pending = goAsync() // keep the process alive across the suspend render
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { QuestWidget().updateAll(context.applicationContext) }
            } finally {
                pending?.finish()
            }
        }
    }
}
