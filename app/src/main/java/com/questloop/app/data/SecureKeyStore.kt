package com.questloop.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException

/** Stores the (sensitive) AI API key, abstracted so production can encrypt it. */
interface SecureKeyStore {
    suspend fun getApiKey(): String
    suspend fun setApiKey(value: String)
    suspend fun clear()
}

/**
 * Default, plaintext store backed by the same DataStore — preserves the original
 * behaviour and keeps unit tests off the Android Keystore.
 */
class DataStoreKeyStore(private val dataStore: DataStore<Preferences>) : SecureKeyStore {
    // Distinct from the legacy "ai_api_key" slot so setAiConfig's legacy scrub
    // doesn't clobber the value this store writes.
    private val key = stringPreferencesKey("ai_api_key_v2")
    override suspend fun getApiKey(): String =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[key].orEmpty()
    override suspend fun setApiKey(value: String) {
        dataStore.edit { it[key] = value }
    }
    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }
}

/**
 * Production store: the key is held in [EncryptedSharedPreferences] backed by a
 * Keystore master key, so it isn't readable in plaintext via adb backup / root.
 */
class EncryptedKeyStore(context: Context) : SecureKeyStore {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "questloop_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // A read failure (e.g. a corrupt/reset keystore) degrades gracefully to "no
    // key" — AI simply turns off. But write failures MUST propagate: silently
    // swallowing them lets the key "save" while persisting nothing, so the user
    // re-enters it and it keeps vanishing with no error. Let setApiKey/clear throw
    // so the caller (SettingsViewModel) can tell the user it couldn't be saved.
    override suspend fun getApiKey(): String = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(KEY, "").orEmpty() }.getOrDefault("")
    }

    override suspend fun setApiKey(value: String) = withContext(Dispatchers.IO) {
        // commit() (synchronous) so a failure surfaces here rather than later on
        // a background apply() that we couldn't observe.
        check(prefs.edit().putString(KEY, value).commit()) { "Could not save the API key securely." }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(KEY).commit()) { "Could not clear the stored API key." }
    }

    private companion object {
        const val KEY = "ai_api_key"
    }
}
