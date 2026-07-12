package com.questloop.app.reminders

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.questloop.app.MainActivity
import com.questloop.app.R
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest

/**
 * Notifications for per-quest timed reminders. Own channel (separate from the
 * daily nudges) so the user can tune or silence quest reminders independently.
 */
object QuestReminderNotifications {
    const val CHANNEL_ID = "questloop_quest_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quest reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders for quests you scheduled at set times."
            },
        )
    }

    /** One stable notification id per quest, clear of the fixed daily-nudge ids
     *  (2001/2002) — a quest's reminders coalesce; different quests don't. */
    fun notificationId(questId: String): Int {
        val h = questId.hashCode()
        return 10_000 + ((h xor (h ushr 16)) and 0x7FFFFF)
    }

    /**
     * The reminder itself. "Mark done" is offered only for styles completable in
     * one tap — binary (done for the day) and counting (+1, e.g. one dose);
     * timed/subjective quests need in-app input, so tapping opens the app instead.
     */
    fun show(context: Context, quest: Quest, epochDay: Long) {
        val builder = builder(context, quest.id, quest.title, "Scheduled for now — whenever you're ready.")
        if (quest.completionStyle == CompletionStyle.BINARY || quest.completionStyle == CompletionStyle.QUANTITATIVE) {
            val doneIntent = Intent(context, QuestReminderActionReceiver::class.java)
                .setAction(QuestReminderActionReceiver.ACTION_QUEST_DONE)
                // Distinct per quest (filterEquals ignores extras — see the scheduler).
                .setData(Uri.parse("questloop://quest-reminder-done/${quest.id}"))
                .putExtra(QuestReminderActionReceiver.EXTRA_QUEST_ID, quest.id)
                // Stamped firing day, so a late tap credits the right day.
                .putExtra(QuestReminderActionReceiver.EXTRA_DAY, epochDay)
            val donePending = PendingIntent.getBroadcast(
                context,
                0,
                doneIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Mark done", donePending)
        }
        post(context, quest.id, builder.build())
    }

    /** Replaces the reminder when "Mark done" found nothing left to credit —
     *  tapping still opens the app (a receiver can't start an activity itself). */
    fun showAlreadyHandled(context: Context, questId: String, title: String?) {
        post(
            context,
            questId,
            builder(context, questId, title ?: "Quest", "Already handled — tap to see today's plan.").build(),
        )
    }

    private fun builder(context: Context, questId: String, title: String, text: String): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            context,
            notificationId(questId),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    // Guarded by areNotificationsEnabled(); the permission check is explicit.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(context: Context, questId: String, notification: Notification) {
        ensureChannel(context)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(notificationId(questId), notification) }
        }
    }
}
