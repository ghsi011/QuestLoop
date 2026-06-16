package com.questloop.app.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import com.questloop.app.data.ReminderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the self-healing reminder mechanism: enabling schedules both slots,
 * disabling cancels them, and a fired alarm re-arms the next one (so the series
 * can't silently end).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderRearmTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val alarmManager get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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
}
