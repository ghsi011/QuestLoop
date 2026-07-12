package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questloop.app.QuestLoopApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Receives a per-quest reminder alarm: re-arms the series, then posts the nudge
 * only if the quest is actually due right now.
 *
 * Unlike the global daily nudge (which posts synchronously before any IO for
 * reliability), a quest reminder is gated on the database first: a "take your
 * medicine" after the course ended — or a rent nudge after it was paid — is
 * worse than a missed nudge on a rare failed read. The series itself still
 * self-heals synchronously from the armed extras, so a failed read never ends it.
 */
class QuestReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuestReminderScheduler.ACTION_QUEST_REMINDER) return
        val questId = intent.getStringExtra(QuestReminderScheduler.EXTRA_QUEST_ID) ?: return
        val index = intent.getIntExtra(QuestReminderScheduler.EXTRA_INDEX, -1)
        val minuteOfDay = intent.getIntExtra(QuestReminderScheduler.EXTRA_MINUTE, -1)
        if (index < 0) return
        // Keep the series alive from the extras alone (same wall-clock time, next
        // day); the reconcile below replaces it with the quest's real cadence or
        // ends it. Must not wait on IO.
        if (minuteOfDay in 0..(24 * 60 - 1)) {
            runCatching { QuestReminderScheduler(context).scheduleFallback(questId, index, minuteOfDay) }
        }
        // Stamp the firing day now, so a reconcile that straddles midnight still
        // notifies (and later credits) the day the alarm actually fired.
        val epochDay = LocalDate.now().toEpochDay()
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    val scheduler = QuestReminderScheduler(context)
                    val eligible = repo.reminderQuests().firstOrNull { it.id == questId }
                    if (eligible == null) {
                        // Archived / reminders off / occurrence limit reached /
                        // deleted: end the series, including the fallback just armed.
                        scheduler.cancelFor(questId)
                    } else {
                        scheduler.schedule(eligible, repo.firstDayOfWeek())
                        val due = repo.reminderDueQuest(questId, epochDay)
                        if (due != null) QuestReminderNotifications.show(context, due, epochDay)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
