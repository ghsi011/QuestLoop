package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
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
        // Show and self-heal synchronously from the armed extras, exactly as the
        // alarm was configured — these guarantees must not wait on IO (a slow
        // config read inside the receiver's async budget could otherwise cost the
        // day's nudge, or silently end the series).
        runCatching { ReminderNotifications.show(context, slot) }
        val hour = intent.getIntExtra(ReminderScheduler.EXTRA_HOUR, -1)
        val minute = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE, 0)
        if (hour in 0..23) {
            runCatching { ReminderScheduler(context).schedule(slot, hour, minute) }
        }
        // Then reconcile with the persisted config: an alarm that was already
        // dispatched when the user toggled reminders off (or moved the time)
        // escaped ReminderScheduler's cancel, and the extras-based re-arm above
        // would perpetuate the stale series. apply() re-derives both slots from
        // the config (or cancels them all when disabled); best-effort — if the
        // read fails, the extras-based series keeps running rather than ending.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    val config = repo.reminderConfig()
                    ReminderScheduler(context).apply(config)
                    if (!config.enabled) {
                        // Retract the nudge we just posted — the user turned them off.
                        NotificationManagerCompat.from(context).cancel(slot.notificationId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
