package com.questloop.app.reminders

import android.app.NotificationManager
import android.content.Context
import com.questloop.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the reminder notifications' shape: the reminder carries the broadcast
 * "Mark done" action, and the already-handled update replaces it (same id)
 * with no actions — its tap opens the app through an activity PendingIntent,
 * the path Android 12+ still allows (unlike an activity start from the
 * action's receiver).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderNotificationsTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val notificationManager
        get() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Test
    fun `reminder carries a broadcast mark-done action and an activity tap`() {
        ReminderNotifications.show(ctx, ReminderSlot.EVENING)

        val posted = shadowOf(notificationManager).getNotification(ReminderSlot.EVENING.notificationId)
        assertNotNull(posted)
        assertEquals(1, posted!!.actions.size)
        assertTrue(shadowOf(posted.actions[0].actionIntent).isBroadcast)
        assertTrue(shadowOf(posted.contentIntent).isActivity)
    }

    @Test
    fun `already-handled update replaces the reminder and opens the app on tap`() {
        ReminderNotifications.show(ctx, ReminderSlot.MORNING)
        ReminderNotifications.showAlreadyHandled(ctx, ReminderSlot.MORNING)

        // Same id: the reminder is replaced, not stacked.
        assertEquals(1, shadowOf(notificationManager).size())
        val posted = shadowOf(notificationManager).getNotification(ReminderSlot.MORNING.notificationId)
        assertNotNull(posted)
        // No broadcast action left — the tap itself is the affordance, and it
        // opens the app (allowed even where receiver-side startActivity isn't).
        assertTrue(posted!!.actions.isNullOrEmpty())
        val contentIntent = posted.contentIntent
        assertNotNull(contentIntent)
        assertTrue(shadowOf(contentIntent).isActivity)
        assertEquals(
            MainActivity::class.java.name,
            shadowOf(contentIntent).savedIntent.component?.className,
        )
    }
}
