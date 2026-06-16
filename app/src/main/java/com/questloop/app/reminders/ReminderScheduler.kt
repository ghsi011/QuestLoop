package com.questloop.app.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import com.questloop.app.data.ReminderConfig

/**
 * Schedules/cancels the daily morning & evening reminder alarms.
 *
 * Uses one-shot inexact-while-idle alarms that each re-arm the next day from the
 * receiver (see [rearm]). A single self-rescheduling alarm is more reliable than
 * a long-lived repeating alarm, which the OS can silently drop (force-stop, app
 * standby, OEM battery managers). Boot and app-open also re-arm as a safety net.
 * Inexact avoids the SCHEDULE_EXACT_ALARM permission; a daily nudge doesn't need
 * to-the-minute precision.
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
        ReminderSlot.entries.forEach { alarmManager?.cancel(pendingIntent(it, 0, 0)) }
    }

    /** Arm the next occurrence of [slot] at [hour]:[minute] (today if still ahead, else tomorrow). */
    fun schedule(slot: ReminderSlot, hour: Int, minute: Int) {
        // +1 min so re-arming right after a fire rolls cleanly to the next day.
        val triggerAt = ReminderSchedule.nextTriggerMillis(System.currentTimeMillis() + 60_000L, hour, minute)
        alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(slot, hour, minute))
    }

    private fun pendingIntent(slot: ReminderSlot, hour: Int, minute: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .putExtra(EXTRA_SLOT, slot.name)
            .putExtra(EXTRA_HOUR, hour)
            .putExtra(EXTRA_MINUTE, minute)
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
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
    }
}
