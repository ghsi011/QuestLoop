package com.questloop.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.questloop.app.data.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Re-arms reminder alarms after device reboot (alarms don't survive reboot). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = ProfileStore(context.applicationContext).getReminderConfig()
                ReminderScheduler(context).apply(config)
            } finally {
                pending.finish()
            }
        }
    }
}
