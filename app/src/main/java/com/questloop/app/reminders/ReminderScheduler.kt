package com.questloop.app.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import com.questloop.app.data.ReminderConfig

/**
 * Schedules/cancels the daily morning & evening reminder alarms.
 *
 * Uses inexact repeating alarms (no exact-alarm permission needed) — a daily
 * nudge doesn't need to-the-minute precision. Alarms don't survive reboot in
 * this MVP; they're re-armed whenever the app is next opened (see QuestLoopApp).
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun apply(config: ReminderConfig) {
        cancelAll()
        if (!config.enabled) return
        schedule(ReminderSlot.MORNING, config.morningHour, config.morningMinute)
        schedule(ReminderSlot.EVENING, config.eveningHour, config.eveningMinute)
    }

    fun cancelAll() {
        ReminderSlot.entries.forEach { alarmManager?.cancel(pendingIntent(it)) }
    }

    private fun schedule(slot: ReminderSlot, hour: Int, minute: Int) {
        val triggerAt = ReminderSchedule.nextTriggerMillis(System.currentTimeMillis(), hour, minute)
        alarmManager?.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(slot),
        )
    }

    private fun pendingIntent(slot: ReminderSlot): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .putExtra(EXTRA_SLOT, slot.name)
        return PendingIntent.getBroadcast(
            context,
            slot.requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_REMINDER = "com.questloop.app.REMINDER"
        const val EXTRA_SLOT = "slot"
    }
}
