package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.questloop.app.QuestLoopApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * "Mark done" on a per-quest reminder notification: completes the quest (binary)
 * or logs one unit toward it (counting — one dose of "twice a day") without
 * opening the app, credited to the day the notification fired.
 */
class QuestReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUEST_DONE) return
        val questId = intent.getStringExtra(EXTRA_QUEST_ID) ?: return
        val epochDay = intent.getLongExtra(EXTRA_DAY, LocalDate.now().toEpochDay())
        // Retract the notification synchronously: action taps don't auto-cancel,
        // and leaving it up until the async write lands invites a double-tap that
        // would credit two doses. If the credit can't land, the "already handled"
        // replacement below (or the quest's next scheduled fire, since it stays
        // due) brings the nudge back.
        runCatching {
            NotificationManagerCompat.from(context).cancel(QuestReminderNotifications.notificationId(questId))
        }
        val expectedCount = intent.getIntExtra(EXTRA_EXPECTED_COUNT, -1).takeIf { it > 0 }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Failures count as "not credited": the notification was already
                // retracted above, so anything short of a landed write must bring a
                // tappable notification back — a swallowed exception would silently
                // eat the dose AND the nudge.
                val landed = runCatching {
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    repo.completeFromReminder(questId, epochDay, expectedCount)
                }.getOrDefault(false)
                if (!landed) {
                    // Nothing credited (already done in-app, needs in-app input, or
                    // the write failed). We can't start an activity from here
                    // (blocked on Android 12+) — replace the notification; tapping
                    // it opens the app via its activity PendingIntent.
                    val title = runCatching {
                        (context.applicationContext as QuestLoopApplication)
                            .container.repository.activeQuestById(questId)?.title
                    }.getOrNull()
                    runCatching { QuestReminderNotifications.showAlreadyHandled(context, questId, title) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_QUEST_DONE = "com.questloop.app.QUEST_REMINDER_DONE"
        const val EXTRA_QUEST_ID = "questId"
        const val EXTRA_DAY = "day"
        const val EXTRA_EXPECTED_COUNT = "expectedCount"
    }
}
