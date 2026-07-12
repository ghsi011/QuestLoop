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
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    if (repo.completeFromReminder(questId, epochDay)) {
                        // Clear only once the completion landed; on failure the
                        // notification (and its idempotent action) stays.
                        NotificationManagerCompat.from(context)
                            .cancel(QuestReminderNotifications.notificationId(questId))
                    } else {
                        // Nothing to credit (already done in-app, or needs in-app
                        // input). We can't start an activity from here (blocked on
                        // Android 12+) — replace the notification; tapping it opens
                        // the app via its activity PendingIntent.
                        val title = repo.activeQuestById(questId)?.title
                        QuestReminderNotifications.showAlreadyHandled(context, questId, title)
                    }
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
    }
}
