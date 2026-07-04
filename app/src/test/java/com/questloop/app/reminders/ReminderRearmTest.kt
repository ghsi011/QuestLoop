package com.questloop.app.reminders

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import com.questloop.app.data.ProfileStore
import com.questloop.app.data.ReminderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Verifies the self-healing reminder mechanism: enabling schedules both slots,
 * disabling cancels them, a fired alarm re-arms the next one (so the series
 * can't silently end), and a timezone or clock change re-arms the pending
 * alarms at the new zone's wall-clock times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderRearmTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val alarmManager get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** [BootReceiver] re-arms from a background coroutine; poll until it lands. */
    private fun awaitRearm(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    /**
     * Delivers [intent] to [receiver] via a real broadcast dispatch, the way the
     * platform does — so `goAsync()`'s PendingResult is wired up and the receiver's
     * `finally { pending.finish() }` doesn't NPE. Robolectric doesn't route an
     * implicit system broadcast to a manifest-declared receiver, so register it for
     * the delivery (the manifest intent-filter is what wires it in production).
     */
    private fun deliver(receiver: BroadcastReceiver, action: String) {
        ctx.registerReceiver(receiver, IntentFilter(action))
        try {
            ctx.sendBroadcast(Intent(action))
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            ctx.unregisterReceiver(receiver)
        }
    }

    @Test
    fun `enabling schedules both slots and disabling cancels them`() {
        val shadow = shadowOf(alarmManager)
        ReminderScheduler(ctx).apply(ReminderConfig(enabled = true, morningHour = 8, eveningHour = 20))
        assertEquals(2, shadow.scheduledAlarms.size)

        ReminderScheduler(ctx).apply(ReminderConfig(enabled = false))
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun `a fired reminder re-arms the next occurrence`() {
        val shadow = shadowOf(alarmManager)
        ReminderScheduler(ctx).cancelAll()
        assertTrue(shadow.scheduledAlarms.isEmpty())

        val intent = Intent(ctx, ReminderReceiver::class.java)
            .putExtra(ReminderScheduler.EXTRA_SLOT, ReminderSlot.MORNING.name)
            .putExtra(ReminderScheduler.EXTRA_HOUR, 8)
            .putExtra(ReminderScheduler.EXTRA_MINUTE, 0)
        ReminderReceiver().onReceive(ctx, intent)

        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test
    fun `a timezone change re-arms both slots at the new zone's wall-clock times`() {
        val shadow = shadowOf(alarmManager)
        val originalZone = TimeZone.getDefault()
        try {
            // Arm in New York, then move the device to Paris.
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val config = ReminderConfig(enabled = true, morningHour = 8, eveningHour = 20)
            runBlocking { ProfileStore(ctx).setReminderConfig(config) }
            ReminderScheduler(ctx).apply(config)

            val paris = ZoneId.of("Europe/Paris")
            fun armedParisTimes() = shadow.scheduledAlarms
                .map { Instant.ofEpochMilli(it.triggerAtTime).atZone(paris).toLocalTime() }
                .toSet()
            val expected = setOf(LocalTime.of(8, 0), LocalTime.of(20, 0))
            // Sanity: instants armed for New York never sit at 08:00/20:00 Paris time
            // (the review's failure case: the 20:00 slot pends at 02:00 Paris time).
            assertNotEquals(expected, armedParisTimes())

            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            deliver(BootReceiver(), Intent.ACTION_TIMEZONE_CHANGED)
            awaitRearm { armedParisTimes() == expected }

            assertEquals(expected, armedParisTimes())
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun `a clock correction re-arms the reminder alarms`() {
        val shadow = shadowOf(alarmManager)
        runBlocking { ProfileStore(ctx).setReminderConfig(ReminderConfig(enabled = true)) }
        ReminderScheduler(ctx).cancelAll()
        assertTrue(shadow.scheduledAlarms.isEmpty())

        // ACTION_TIME_CHANGED is the "android.intent.action.TIME_SET" broadcast.
        deliver(BootReceiver(), Intent.ACTION_TIME_CHANGED)
        awaitRearm { shadow.scheduledAlarms.size == 2 }

        assertEquals(2, shadow.scheduledAlarms.size)
    }
}
