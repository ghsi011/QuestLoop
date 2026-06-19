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

/**
 * Stores sensitive AI credentials, abstracted so production can encrypt them: the
 * OpenRouter API key and the OpenAI OAuth token bundle (held as an opaque JSON
 * blob — [ProfileStore] owns its shape).
 */
interface SecureKeyStore {
    suspend fun getApiKey(): String
    suspend fun setApiKey(value: String)

    /** The OpenAI OAuth tokens as a JSON blob, or "" when not signed in. */
    suspend fun getOpenAiTokens(): String
    suspend fun setOpenAiTokens(value: String)

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
    private val openAiKey = stringPreferencesKey("openai_tokens_v1")

    private suspend fun read(k: Preferences.Key<String>): String =
        dataStore.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[k].orEmpty()

    override suspend fun getApiKey(): String = read(key)
    override suspend fun setApiKey(value: String) {
        dataStore.edit { it[key] = value }
    }

    override suspend fun getOpenAiTokens(): String = read(openAiKey)
    override suspend fun setOpenAiTokens(value: String) {
        dataStore.edit { if (value.isBlank()) it.remove(openAiKey) else it[openAiKey] = value }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(key); it.remove(openAiKey) }
    }
}

/**
 * Production store: credentials are held in [EncryptedSharedPreferences] backed by
 * a Keystore master key, so they aren't readable in plaintext via adb backup / root.
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
    // credential" — AI simply turns off. But write failures MUST propagate: silently
    // swallowing them lets the value "save" while persisting nothing, so the user
    // re-enters it and it keeps vanishing with no error. Let setters/clear throw
    // so the caller (SettingsViewModel) can tell the user it couldn't be saved.
    override suspend fun getApiKey(): String = read(KEY)

    override suspend fun setApiKey(value: String) = write(KEY, value)

    override suspend fun getOpenAiTokens(): String = read(KEY_OPENAI)

    override suspend fun setOpenAiTokens(value: String) = write(KEY_OPENAI, value)

    override suspend fun clear() = withContext(Dispatchers.IO) {
        check(prefs.edit().remove(KEY).remove(KEY_OPENAI).commit()) { "Could not clear the stored credentials." }
    }

    private suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(name, "").orEmpty() }.getOrDefault("")
    }

    private suspend fun write(name: String, value: String) = withContext(Dispatchers.IO) {
        // commit() (synchronous) so a failure surfaces here rather than later on
        // a background apply() that we couldn't observe.
        check(prefs.edit().putString(name, value).commit()) { "Could not save the credential securely." }
    }

    private companion object {
        const val KEY = "ai_api_key"
        const val KEY_OPENAI = "openai_tokens"
    }
}
