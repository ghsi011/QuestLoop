package com.questloop.app.reminders

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.core.content.ContextCompat
import com.questloop.app.QuestLoopApplication
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.DayPart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * completion has landed — crediting the day the notification *fired* (so a tap
 * after midnight lands on the right day), and when nothing is left to complete it
 * replaces the reminder instead of crediting XP. It never starts an activity (an
 * activity start from a notification action's receiver is a trampoline, silently
 * blocked on Android 12+).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderActionReceiverTest {

    private val app: QuestLoopApplication
        get() = RuntimeEnvironment.getApplication() as QuestLoopApplication
    private val notificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val repo get() = app.container.repository

    // The receiver and this test share the app's single wired repository (same
    // singleton DB), so a clean slate before each test keeps them independent. Clear
    // the ledger + quests directly (not repository.deleteAllData(), whose ProfileStore
    // wipe reaches the AndroidKeyStore that Robolectric doesn't provide).
    @Before
    fun clearLedger() = runBlocking {
        val db = QuestLoopDatabase.get(app)
        db.completionDao().clear()
        db.questDao().clear()
    }

    /**
     * Delivers the "Mark done" broadcast via a real dispatch so `goAsync()`'s
     * PendingResult is wired up (a direct onReceive() leaves it null, and the
     * receiver's finally-block finish() then NPEs on its background thread).
     * Robolectric doesn't route a sendBroadcast to the manifest-declared receiver,
     * so register it for the delivery — the manifest registration is what wires it
     * in production. The receiver still does its real work on Dispatchers.IO.
     */
    private fun markDone(slot: ReminderSlot, epochDay: Long) {
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
                    .putExtra(ReminderActionReceiver.EXTRA_SLOT, slot.name)
                    .putExtra(ReminderActionReceiver.EXTRA_DAY, epochDay),
            )
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            app.unregisterReceiver(receiver)
        }
    }

    @Test
    fun `mark done completes the routine directly and then clears the notification`() {
        val day = LocalDate.now().toEpochDay()
        ReminderNotifications.show(app, ReminderSlot.MORNING)
        assertNotNull(shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId))

        markDone(ReminderSlot.MORNING, day)

        // The receiver works on Dispatchers.IO; cancelling the notification is its
        // last step, so waiting for that covers the whole completion path.
        awaitUntil {
            shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId) == null
        }

        val record = runBlocking { repo.completions.first() }
            .firstOrNull { it.questId == RoutineQuestFactory.MORNING_REVIEW }
        assertNotNull("expected the morning routine to be completed", record)
        assertEquals(CompletionResult.COMPLETED, record!!.result)
        assertEquals(day, record.epochDay)
        // Direct completion only — the receiver must never launch an activity.
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a late mark done credits the stamped day, not today`() {
        // The notification fired yesterday; the user taps "done" after midnight. The
        // stamped EXTRA_DAY must win so the completion lands on the day it belongs to.
        val yesterday = LocalDate.now().toEpochDay() - 1

        markDone(ReminderSlot.MORNING, yesterday)
        awaitUntil { runBlocking { repo.completions.first() }.isNotEmpty() }

        val record = runBlocking { repo.completions.first() }
            .firstOrNull { it.questId == RoutineQuestFactory.MORNING_REVIEW }
        assertNotNull("expected the morning routine to be completed", record)
        assertEquals("credited to the stamped day, not today", yesterday, record!!.epochDay)
        assertEquals(CompletionResult.COMPLETED, record.result)
    }

    @Test
    fun `mark done with nothing left to complete opens the app instead of crediting xp`() {
        val yesterday = LocalDate.now().toEpochDay() - 1
        val morning = RoutineQuestFactory.routinesFor(DayPart.MORNING).first()
        // Already done in-app for that day, so it has dropped out of the plan (W01).
        runBlocking { repo.completeQuest(morning, yesterday, CompletionResult.COMPLETED) }
        assertNull(
            "precondition: the routine is no longer planned",
            runBlocking { repo.todayPlan(yesterday, DayPart.MORNING) }.quests
                .map { it.quest }.firstOrNull { it.id == morning.id },
        )
        val xpBefore = runBlocking { repo.totalXp() }
        val countBefore = runBlocking { repo.completions.first() }.size

        markDone(ReminderSlot.MORNING, yesterday)
        // The not-planned branch posts the "already handled" reminder (tap opens the app).
        awaitUntil {
            shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId) != null
        }

        assertEquals("no second completion is written", countBefore, runBlocking { repo.completions.first() }.size)
        assertEquals("no extra XP is credited", xpBefore, runBlocking { repo.totalXp() })
        // Replaced the reminder rather than starting an activity (trampolines are blocked).
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
