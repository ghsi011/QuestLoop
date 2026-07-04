package com.questloop.app.widget

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies the widget's self-healing day-boundary refresh: the alarm is armed
 * only while a widget instance exists, points at a future boundary, and a fired
 * refresh re-arms the next one (so the series can't silently end).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRefreshRearmTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val alarmManager get() = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun bindWidget() {
        shadowOf(AppWidgetManager.getInstance(ctx))
            .bindAppWidgetId(42, ComponentName(ctx, QuestWidgetReceiver::class.java))
    }

    @Test
    fun `no widget placed means no alarm`() {
        val shadow = shadowOf(alarmManager)
        WidgetRefreshScheduler(ctx).scheduleNext()
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }

    @Test
    fun `a placed widget arms one alarm at a future boundary`() {
        val shadow = shadowOf(alarmManager)
        bindWidget()
        WidgetRefreshScheduler(ctx).scheduleNext()
        assertEquals(1, shadow.scheduledAlarms.size)
        assertTrue(shadow.scheduledAlarms.single().triggerAtTime > System.currentTimeMillis())
    }

    @Test
    fun `a fired refresh re-arms the next boundary`() {
        val shadow = shadowOf(alarmManager)
        bindWidget()
        val intent = Intent(ctx, WidgetRefreshReceiver::class.java)
            .setAction(WidgetRefreshScheduler.ACTION_REFRESH)
        WidgetRefreshReceiver().onReceive(ctx, intent)
        assertEquals(1, shadow.scheduledAlarms.size)
    }

    @Test
    fun `a foreign action is ignored`() {
        val shadow = shadowOf(alarmManager)
        bindWidget()
        WidgetRefreshReceiver().onReceive(ctx, Intent(ctx, WidgetRefreshReceiver::class.java))
        assertTrue(shadow.scheduledAlarms.isEmpty())
    }
}
