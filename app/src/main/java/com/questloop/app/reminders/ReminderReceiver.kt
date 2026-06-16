package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the daily alarm, posts the reminder, and re-arms the next day. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(ReminderScheduler.EXTRA_SLOT)
            ?.let { runCatching { ReminderSlot.valueOf(it) }.getOrNull() }
            ?: return
        runCatching { ReminderNotifications.show(context, slot) }
        // Self-heal: schedule the next occurrence so the series can't silently end.
        val hour = intent.getIntExtra(ReminderScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, 0)
        if (hour in 0..23) {
            runCatching { ReminderScheduler(context).schedule(slot, hour, minute) }
        }
    }
}
