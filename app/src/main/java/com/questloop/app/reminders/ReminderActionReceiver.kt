package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.questloop.app.data.ProfileStore
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.local.QuestLoopDatabase
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

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = QuestLoopDatabase.get(context)
                val repo = QuestRepository(db.questDao(), db.completionDao(), ProfileStore(context.applicationContext))
                val dayPart = if (slot == ReminderSlot.MORNING) DayPart.MORNING else DayPart.EVENING
                val quest = RoutineQuestFactory.routinesFor(dayPart).firstOrNull()
                if (quest != null) {
                    repo.completeQuest(quest, LocalDate.now().toEpochDay(), CompletionResult.COMPLETED)
                }
                NotificationManagerCompat.from(context).cancel(slot.notificationId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.questloop.app.REMINDER_DONE"
        const val EXTRA_SLOT = "slot"
    }
}
