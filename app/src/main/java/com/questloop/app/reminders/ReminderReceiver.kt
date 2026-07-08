package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questloop.app.QuestLoopApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Receives the daily alarm, posts the reminder, and re-arms the next day. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(ReminderScheduler.EXTRA_SLOT)
            ?.let { runCatching { ReminderSlot.valueOf(it) }.getOrNull() }
            ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(context, slot, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, slot: ReminderSlot, intent: Intent) {
        // Re-read the config instead of trusting the armed intent: an alarm that was
        // already dispatched when the user toggled reminders off (or moved the time)
        // escapes ReminderScheduler's cancel, and re-arming from its stale extras
        // would perpetuate the old series forever. Fall back to the extras only if
        // the config read fails — a missed nudge is worse than a stale-timed one.
        val config = runCatching {
            (context.applicationContext as QuestLoopApplication).container.repository.reminderConfig()
        }.getOrNull()
        if (config?.enabled == false) return
        runCatching { ReminderNotifications.show(context, slot) }
        // Self-heal: schedule the next occurrence so the series can't silently end.
        val (hour, minute) = when {
            config != null && slot == ReminderSlot.MORNING -> config.morningHour to config.morningMinute
            config != null -> config.eveningHour to config.eveningMinute
            else -> intent.getIntExtra(ReminderScheduler.EXTRA_HOUR, -1) to
                intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, 0)
        }
        if (hour in 0..23) {
            runCatching { ReminderScheduler(context).schedule(slot, hour, minute) }
        }
    }
}
