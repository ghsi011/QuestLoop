package com.questloop.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.questloop.core.model.BadHabit
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        val profile = store.profile.first()
        assertEquals(emptyList<Habit>(), profile.habits)
        assertEquals(6, profile.preferences.maxDailyQuests)
    }

    @Test
    fun `list setters refuse to overwrite a stored list that no longer decodes`() = runTest {
        val ds = realDataStore()
        ds.edit {
            it[stringPreferencesKey("habits_json")] = "{not valid json"
            it[stringPreferencesKey("bad_habits_json")] = "{not valid json"
            it[stringPreferencesKey("goals_json")] = "{not valid json"
        }
        val store = ProfileStore(ctx, ds)
        // The read side degrades to empty lists (test above), so a read-modify-write
        // built on it would persist a near-empty list over the user's real data.
        val habit = Habit(id = "h1", title = "Stretch", category = QuestCategory.HEALTH)
        val bad = BadHabit(id = "b1", title = "Doomscrolling")
        val goal = Goal(id = "g1", title = "Run a 5k", category = QuestCategory.HEALTH)
        assertTrue(runCatching { store.setHabits(listOf(habit)) }.exceptionOrNull() is ProfileListCorruptException)
        assertTrue(runCatching { store.setBadHabits(listOf(bad)) }.exceptionOrNull() is ProfileListCorruptException)
        assertTrue(runCatching { store.setGoals(listOf(goal)) }.exceptionOrNull() is ProfileListCorruptException)
        // The raw blobs survive for a fixed app version (or export) to recover.
        val prefs = ds.data.first()
        assertEquals("{not valid json", prefs[stringPreferencesKey("habits_json")])
        assertEquals("{not valid json", prefs[stringPreferencesKey("bad_habits_json")])
        assertEquals("{not valid json", prefs[stringPreferencesKey("goals_json")])
    }

    @Test
    fun `list setters replace absent and decodable blobs normally`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds)
        val first = Habit(id = "h1", title = "Stretch", category = QuestCategory.HEALTH)
        val second = Habit(id = "h2", title = "Read", category = QuestCategory.PERSONAL_GROWTH)
        store.setHabits(listOf(first)) // no blob yet
        store.setHabits(listOf(first, second)) // a decodable blob stays replaceable
        assertEquals(listOf("h1", "h2"), store.profile.first().habits.map { it.id })
    }

    @Test
    fun `ai config round-trips and is not left in plaintext datastore`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        store.setAiConfig(AiConfig(enabled = true, apiKey = "sk-xyz", model = "model-1"))
        val cfg = store.getAiConfig()
        assertEquals(true, cfg.enabled)
        assertEquals("sk-xyz", cfg.apiKey)
        assertEquals("model-1", cfg.model)
        // The test key store keeps the key out of the legacy plaintext slot.
        assertNull(ds.data.first()[androidx.datastore.preferences.core.stringPreferencesKey("ai_api_key")])
    }

    @Test
    fun `a lingering legacy plaintext copy is scrubbed once the secure store holds the key`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds)
        store.setAiConfig(AiConfig(enabled = true, apiKey = "sk-xyz"))
        // Simulate a process death between the migration's verified secure write and
        // its plaintext remove: the legacy slot still holds a stale plaintext copy.
        ds.edit { it[stringPreferencesKey("ai_api_key")] = "sk-xyz" }
        assertEquals("sk-xyz", store.getAiConfig().apiKey)
        // The read notices the secure store already has the key and scrubs the copy.
        assertNull(ds.data.first()[stringPreferencesKey("ai_api_key")])
    }

    @Test
    fun `openai provider and oauth tokens round-trip`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        val tokens = com.questloop.core.ai.openai.OpenAiOAuth.OpenAiTokens(
            accessToken = "at-1",
            refreshToken = "rt-1",
            accountId = "acct-1",
            expiresAtEpochSec = 9_999,
        )
        store.setAiConfig(
            AiConfig(enabled = true, provider = AiProvider.OPENAI, openAiTokens = tokens, openAiModel = "gpt-5-codex"),
        )
        val cfg = store.getAiConfig()
        assertEquals(AiProvider.OPENAI, cfg.provider)
        assertEquals("gpt-5-codex", cfg.openAiModel)
        assertEquals(tokens, cfg.openAiTokens)
        assertEquals(true, cfg.openAiConnected)
        assertEquals(true, cfg.usable)
    }

    @Test
    fun `clearing the openai tokens unlinks the account`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        val tokens = com.questloop.core.ai.openai.OpenAiOAuth.OpenAiTokens("at", "rt", accountId = "a")
        store.setAiConfig(AiConfig(enabled = true, provider = AiProvider.OPENAI, openAiTokens = tokens))
        assertEquals(true, store.getAiConfig().openAiConnected)
        store.setAiConfig(store.getAiConfig().copy(openAiTokens = null))
        assertNull(store.getAiConfig().openAiTokens)
    }

    @Test
    fun `io error on read falls back to defaults without crashing`() = runTest {
        val ds = throwingDataStore(IOException("corrupt"))
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        assertEquals(6, store.profile.first().preferences.maxDailyQuests)
        assertFalse(store.isOnboardingComplete())
        assertFalse(store.getAiConfig().usable)
    }

    @Test
    fun `a non-io error on read is rethrown, not swallowed`() {
        val ds = throwingDataStore(IllegalStateException("boom"))
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { store.profile.first() }
        }
    }

    @Test
    fun `streak grace days are clamped to the 0 to 7 range`() = runTest {
        val ds = realDataStore()
        val store = ProfileStore(ctx, ds, DataStoreKeyStore(ds))
        // Above the cap clamps down to 7…
        store.setStreakGraceDays(99)
        assertEquals(7, store.profile.first().preferences.streakGraceDays)
        // …below the floor clamps up to 0…
        store.setStreakGraceDays(-5)
        assertEquals(0, store.profile.first().preferences.streakGraceDays)
        // …and an in-range value is kept as-is.
        store.setStreakGraceDays(3)
        assertEquals(3, store.profile.first().preferences.streakGraceDays)
    }
}
