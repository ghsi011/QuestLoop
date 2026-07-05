package com.questloop.app.reminders

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import com.questloop.app.QuestLoopApplication
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.CompletionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Covers the notification "Mark done" action end-to-end: the receiver completes
 * the slot's routine directly and clears the notification only after the
 * completion has landed — and it never starts an activity (an activity start
 * from a notification action's receiver is a trampoline, silently blocked on
 * Android 12+).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderActionReceiverTest {

    private val app: QuestLoopApplication
        get() = RuntimeEnvironment.getApplication() as QuestLoopApplication
    private val notificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `mark done completes the routine directly and then clears the notification`() {
        val day = LocalDate.now().toEpochDay()
        ReminderNotifications.show(app, ReminderSlot.MORNING)
        assertNotNull(shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId))

        // Deliver via a real broadcast dispatch so goAsync()'s PendingResult is wired
        // up (a direct onReceive() leaves it null, and the receiver's finally-block
        // finish() then NPEs on its background thread). Robolectric doesn't route a
        // sendBroadcast to the manifest-declared receiver, so register it for the
        // delivery — the manifest registration is what wires it in production. The
        // receiver still does its real work asynchronously on Dispatchers.IO.
        val receiver = ReminderActionReceiver()
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(ReminderActionReceiver.ACTION_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            app.sendBroadcast(
                Intent(ReminderActionReceiver.ACTION_DONE)
                    .putExtra(ReminderActionReceiver.EXTRA_SLOT, ReminderSlot.MORNING.name)
                    .putExtra(ReminderActionReceiver.EXTRA_DAY, day),
            )
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            app.unregisterReceiver(receiver)
        }

        // The receiver works on Dispatchers.IO; cancelling the notification is its
        // last step, so waiting for that covers the whole completion path.
        awaitUntil {
            shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId) == null
        }

        val record = runBlocking { app.container.repository.completions.first() }
            .firstOrNull { it.questId == RoutineQuestFactory.MORNING_REVIEW }
        assertNotNull("expected the morning routine to be completed", record)
        assertEquals(CompletionResult.COMPLETED, record!!.result)
        assertEquals(day, record.epochDay)
        // Direct completion only — the receiver must never launch an activity.
        assertNull(shadowOf(app).nextStartedActivity)
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("condition not met within ${timeoutMs}ms", System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        }
    }
}
