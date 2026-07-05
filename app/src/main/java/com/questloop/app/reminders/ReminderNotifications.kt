package com.questloop.app.reminders

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.questloop.app.MainActivity
import com.questloop.app.R

/** The two reminder occurrences and their copy. */
enum class ReminderSlot(val requestCode: Int, val notificationId: Int, val title: String, val text: String) {
    MORNING(1001, 2001, "Good morning ☀️", "Take a quick look at today."),
    EVENING(1002, 2002, "Evening check-in 🌙", "Check off what you finished and plan tomorrow."),
}

object ReminderNotifications {
    const val CHANNEL_ID = "questloop_reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Gentle morning and evening nudges for your daily loop."
            },
        )
    }

    fun show(context: Context, slot: ReminderSlot) {
        val doneIntent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderActionReceiver.ACTION_DONE)
            .putExtra(ReminderActionReceiver.EXTRA_SLOT, slot.name)
            // Stamp the firing day so a late "Mark done" credits the correct day.
            .putExtra(ReminderActionReceiver.EXTRA_DAY, java.time.LocalDate.now().toEpochDay())
        val donePending = PendingIntent.getBroadcast(
            context,
            slot.requestCode + 500,
            doneIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(context, slot, builder(context, slot, slot.text).addAction(0, "Mark done", donePending).build())
    }

    /**
     * Replaces the slot's reminder when "Mark done" found nothing left to
     * complete (the check-in was already done in the app, or skipped for today).
     * The receiver can't open the app itself — Android 12+ silently blocks
     * activity starts from a notification action's receiver — but tapping this
     * notification still can, via its activity PendingIntent.
     */
    fun showAlreadyHandled(context: Context, slot: ReminderSlot) {
        post(context, slot, builder(context, slot, "Already handled — tap to see today's plan.").build())
    }

    /** Shared shape: tapping the notification opens the app. */
    private fun builder(context: Context, slot: ReminderSlot, text: String): NotificationCompat.Builder {
        val openApp = PendingIntent.getActivity(
            context,
            slot.requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(slot.title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    }

    // Guarded by areNotificationsEnabled(); the permission check is explicit.
    @SuppressLint("MissingPermission", "NotificationPermission")
    private fun post(context: Context, slot: ReminderSlot, notification: Notification) {
        ensureChannel(context)
        // Guarded: on Android 13+ this is a no-op if the user hasn't granted POST_NOTIFICATIONS.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            runCatching { NotificationManagerCompat.from(context).notify(slot.notificationId, notification) }
        }
    }
}
