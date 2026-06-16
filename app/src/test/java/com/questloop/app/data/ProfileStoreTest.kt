package com.questloop.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.questloop.core.model.Habit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Tests ProfileStore's resilience to a corrupt prefs file / malformed JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileStoreTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tempFiles = mutableListOf<File>()

    @After
    fun tearDown() = tempFiles.forEach { it.delete() }

    private fun realDataStore(): DataStore<Preferences> {
        val file = File.createTempFile("prefs", ".preferences_pb").also { it.delete(); tempFiles += it }
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private fun throwingDataStore(error: Throwable) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw error
    }

    @Test
    fun `malformed habits json decodes to an empty list and keeps defaults`() = runTest {
        val ds = realDataStore()
        ds.edit { it[stringPreferencesKey("habits_json")] = "{not valid json" }
        val store = ProfileStore(ctx, ds)
        val profile = store.profile.first()
        assertEquals(emptyList<Habit>(), profile.habits)
        assertEquals(6, profile.preferences.maxDailyQuests)
    }

    @Test
    fun `io error on read falls back to defaults without crashing`() = runTest {
        val store = ProfileStore(ctx, throwingDataStore(IOException("corrupt")))
        assertEquals(6, store.profile.first().preferences.maxDailyQuests)
        assertFalse(store.isOnboardingComplete())
        assertFalse(store.getAiConfig().usable)
    }

    @Test
    fun `a non-io error on read is rethrown, not swallowed`() {
        val store = ProfileStore(ctx, throwingDataStore(IllegalStateException("boom")))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.profile.first() }
        }
    }
}
