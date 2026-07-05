package com.questloop.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the production credential store ([EncryptedKeyStore]) against the real
 * EncryptedSharedPreferences + Keystore. Emulator images ship a software-backed
 * Keystore, so this is the one place its contract actually runs under assertion
 * (Robolectric has no Keystore at all). Pins what [com.questloop.app.di.AppContainer]
 * relies on: reads degrade to "" when nothing is stored, writes persist across
 * instances, blank writes overwrite rather than throw, and clear() removes both
 * credentials.
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker) like the other
 * instrumented tests.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedKeyStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = EncryptedKeyStore(context)

    // The store writes the app's real "questloop_secure" prefs file; start and end
    // each test blank so no credential state leaks into the other instrumented tests.
    @Before
    fun startBlank() = runBlocking { store.clear() }

    @After
    fun endBlank() = runBlocking { store.clear() }

    @Test
    fun api_key_round_trips_and_persists_across_instances() = runBlocking {
        assertEquals("", store.getApiKey())
        store.setApiKey("sk-or-roundtrip")
        assertEquals("sk-or-roundtrip", store.getApiKey())
        // A fresh instance re-opens the encrypted prefs and decrypts the same value.
        assertEquals("sk-or-roundtrip", EncryptedKeyStore(context).getApiKey())
    }

    @Test
    fun openai_tokens_round_trip_independently_of_the_api_key() = runBlocking {
        assertEquals("", store.getOpenAiTokens())
        store.setApiKey("sk-or-key")
        store.setOpenAiTokens("""{"accessToken":"at-1","refreshToken":"rt-1"}""")
        assertEquals("""{"accessToken":"at-1","refreshToken":"rt-1"}""", store.getOpenAiTokens())
        // The credentials live under separate slots; neither write clobbers the other.
        assertEquals("sk-or-key", store.getApiKey())
    }

    @Test
    fun blank_writes_overwrite_to_blank_instead_of_throwing() = runBlocking {
        store.setApiKey("sk-or-key")
        store.setOpenAiTokens("tokens")
        store.setApiKey("")
        store.setOpenAiTokens("")
        assertEquals("", store.getApiKey())
        assertEquals("", store.getOpenAiTokens())
    }

    @Test
    fun clear_removes_both_credentials() = runBlocking {
        store.setApiKey("sk-or-key")
        store.setOpenAiTokens("tokens")
        store.clear()
        assertEquals("", store.getApiKey())
        assertEquals("", EncryptedKeyStore(context).getOpenAiTokens())
    }
}
