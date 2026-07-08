package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.questloop.app.QuestLoopApplication
import com.questloop.app.widget.QuestWidget
import com.questloop.app.widget.WidgetRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-arms reminder alarms when the armed instants go stale: after device reboot
 * (alarms don't survive it) and after timezone or clock changes (alarms are fixed
 * epoch instants, so a pending one would otherwise fire at the old zone's — or
 * old clock's — wall time).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in REARM_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching {
                    // Reuse the app's single wired repository (EncryptedKeyStore etc.)
                    // instead of hand-wiring a divergent ProfileStore.
                    val repo = (context.applicationContext as QuestLoopApplication).container.repository
                    ReminderScheduler(context).apply(repo.reminderConfig())
                }
                // The widget's day-boundary alarm is a fixed epoch instant with the
                // same stale-on-reboot/zone-change property, and its other re-arm
                // paths (Application.onCreate, widget add) don't run here when the
                // process is already alive — so re-arm and re-render it too.
                runCatching { WidgetRefreshScheduler(context).scheduleNext() }
                runCatching { QuestWidget().updateAll(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        // ACTION_TIME_CHANGED is the "android.intent.action.TIME_SET" broadcast.
        val REARM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
