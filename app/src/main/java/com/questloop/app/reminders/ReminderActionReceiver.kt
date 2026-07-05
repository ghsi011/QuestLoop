package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.questloop.app.QuestLoopApplication
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.DayPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Handles the "Mark done" action on a reminder notification — completes that
 * slot's routine quest without opening the app (the minimal-interaction win).
 */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val slot = intent.getStringExtra(EXTRA_SLOT)
            ?.let { runCatching { ReminderSlot.valueOf(it) }.getOrNull() }
            ?: return

        // The day the notification fired (stamped at show time), so tapping "done"
        // after midnight still credits the right day. Falls back to today.
        val epochDay = intent.getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay())

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    // Reuse the app's single wired repository (EncryptedKeyStore etc.)
                    // instead of hand-wiring a divergent one.
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    val dayPart = if (slot == ReminderSlot.MORNING) DayPart.MORNING else DayPart.EVENING
                    // Only complete a routine that's actually in today's plan — never
                    // credit XP to a synthetic quest the user can't see.
                    val routineIds = RoutineQuestFactory.routinesFor(dayPart).map { it.id }.toSet()
                    val planned = repo.todayPlan(epochDay, dayPart).quests
                        .map { it.quest }
                        .firstOrNull { it.id in routineIds }
                    if (planned != null) {
                        repo.completeQuest(planned, epochDay, CompletionResult.COMPLETED)
                        // Clear the reminder only now that the completion landed; if it
                        // failed, the notification (and its idempotent action) stays.
                        NotificationManagerCompat.from(context).cancel(slot.notificationId)
                    } else {
                        // Nothing completable (already checked off in-app, or dismissed
                        // for today). We can't start an activity from here — that's a
                        // notification trampoline, silently blocked on Android 12+ — so
                        // update the notification instead: tapping it opens the app via
                        // its activity PendingIntent, which is allowed.
                        ReminderNotifications.showAlreadyHandled(context, slot)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.questloop.app.REMINDER_DONE"
        const val EXTRA_SLOT = "slot"
        const val EXTRA_DAY = "day"
    }
}
