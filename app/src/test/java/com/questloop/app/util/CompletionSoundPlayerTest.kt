package com.questloop.app.util

import android.app.Application
import android.app.NotificationManager
import android.media.AudioManager
import com.questloop.app.R
import com.questloop.core.reward.CompletionChime
import com.questloop.core.reward.CompletionSound
import org.junit.After
import org.junit.Assert.assertFalse
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
import org.robolectric.shadows.ShadowSoundPool

/**
 * The audibility gates and the load-window queue of [CompletionSoundPlayer]:
 * chimes play once their sample loads, mute states (silent/vibrate ringer, Do
 * Not Disturb) suppress them, and a failed sample load drops its queued plays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompletionSoundPlayerTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @Before
    fun startCold() = CompletionSoundPlayer.resetForTesting()

    @After
    fun tearDown() = CompletionSoundPlayer.resetForTesting()

    private fun shadowPool(): ShadowSoundPool = shadowOf(CompletionSoundPlayer.poolForTesting()!!)

    private fun minor(volume: Float = 0.8f) = CompletionSound(CompletionChime.MINOR, volume)

    @Test
    fun `chime plays once its sample loads`() {
        CompletionSoundPlayer.play(context, minor())
        val pool = shadowPool()
        assertFalse(pool.wasResourcePlayed(R.raw.chime_minor))
        pool.notifyResourceLoaded(R.raw.chime_minor, true)
        assertTrue(pool.wasResourcePlayed(R.raw.chime_minor))
    }

    @Test
    fun `a burst during the load window survives - every queued chime plays`() {
        CompletionSoundPlayer.play(context, minor())
        CompletionSoundPlayer.play(context, CompletionSound(CompletionChime.MAJOR, 1f))
        val pool = shadowPool()
        pool.notifyResourceLoaded(R.raw.chime_minor, true)
        pool.notifyResourceLoaded(R.raw.chime_major, true)
        assertTrue(pool.wasResourcePlayed(R.raw.chime_minor))
        assertTrue(pool.wasResourcePlayed(R.raw.chime_major))
    }

    @Test
    fun `a failed sample load drops its queued plays`() {
        CompletionSoundPlayer.play(context, minor())
        val pool = shadowPool()
        pool.notifyResourceLoaded(R.raw.chime_minor, false)
        // Even if the resource later reports loaded, the dropped requests stay dropped.
        pool.notifyResourceLoaded(R.raw.chime_minor, true)
        assertFalse(pool.wasResourcePlayed(R.raw.chime_minor))
    }

    @Test
    fun `a loaded sample plays immediately on the next completion`() {
        CompletionSoundPlayer.play(context, minor())
        val pool = shadowPool()
        pool.notifyResourceLoaded(R.raw.chime_minor, true)
        pool.clearPlayed()
        CompletionSoundPlayer.play(context, minor())
        assertTrue(pool.wasResourcePlayed(R.raw.chime_minor))
    }

    @Test
    fun `silent ringer suppresses the chime entirely`() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_SILENT
        CompletionSoundPlayer.play(context, minor())
        assertNull(CompletionSoundPlayer.poolForTesting())
    }

    @Test
    fun `vibrate ringer suppresses the chime entirely`() {
        context.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_VIBRATE
        CompletionSoundPlayer.play(context, minor())
        assertNull(CompletionSoundPlayer.poolForTesting())
    }

    @Test
    fun `do not disturb suppresses the chime entirely`() {
        context.getSystemService(NotificationManager::class.java)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        CompletionSoundPlayer.play(context, minor())
        assertNull(CompletionSoundPlayer.poolForTesting())
    }

    @Test
    fun `normal ringer with no dnd plays`() {
        CompletionSoundPlayer.play(context, minor())
        assertNotNull(CompletionSoundPlayer.poolForTesting())
    }
}
