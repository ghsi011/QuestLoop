package com.questloop.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "questloop_profile")

/**
 * Lightweight profile/preferences the repository depends on. Extracted as an
 * interface so the repository can be unit-tested with a simple fake.
 */
interface ProfilePreferences {
    val profile: Flow<UserProfile>
    suspend fun setBudgetCap(value: Double)
    suspend fun setMaxDaily(value: Int)
    suspend fun setAvailableMinutes(value: Int)
    suspend fun setFocusCategories(cats: Set<QuestCategory>)
    suspend fun setHabits(habits: List<Habit>)
    suspend fun setBadHabits(badHabits: List<BadHabit>)
    suspend fun setGoals(goals: List<Goal>)
    suspend fun setCheckIn(checkIn: EnergyCheckIn?)
    suspend fun getCheckIn(): EnergyCheckIn?
    /** AI config is kept out of [profile]/export so the API key is never exported. */
    suspend fun getAiConfig(): AiConfig
    suspend fun setAiConfig(config: AiConfig)
    suspend fun isOnboardingComplete(): Boolean
    suspend fun setOnboardingComplete()
    suspend fun getReminderConfig(): ReminderConfig
    suspend fun setReminderConfig(config: ReminderConfig)
    suspend fun clear()
}

/**
 * Stores lightweight profile + preference state in DataStore: scalar settings as
 * typed prefs, and the habit/bad-habit/goal lists as JSON (the core models are
 * @Serializable). Quests, completion history, and total XP live in Room.
 */
class ProfileStore(
    context: Context,
    private val dataStore: DataStore<Preferences> = context.dataStore,
    private val keyStore: SecureKeyStore = DataStoreKeyStore(dataStore),
) : ProfilePreferences {

    private val json = Json { ignoreUnknownKeys = true }

    // A corrupt preferences file surfaces as IOException on read; fall back to
    // empty defaults rather than crashing (e.g. the onboarding check on launch).
    private val safeData: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private object Keys {
        val MAX_DAILY = intPreferencesKey("max_daily_quests")
        val AVAILABLE_MIN = intPreferencesKey("default_available_minutes")
        val BUDGET_CAP = doublePreferencesKey("monthly_reward_budget_cap")
        val GRACE_DAYS = intPreferencesKey("streak_grace_days")
        val SENSITIVE_OPT_IN = intPreferencesKey("sensitive_opt_in")
        val FOCUS = stringSetPreferencesKey("focus_categories")
        val HABITS = stringPreferencesKey("habits_json")
        val BAD_HABITS = stringPreferencesKey("bad_habits_json")
        val GOALS = stringPreferencesKey("goals_json")
        val CHECKIN_DAY = longPreferencesKey("checkin_day")
        val CHECKIN_ENERGY = intPreferencesKey("checkin_energy")
        val CHECKIN_MIN = intPreferencesKey("checkin_minutes")
        val AI_ENABLED = intPreferencesKey("ai_enabled")
        val AI_KEY = stringPreferencesKey("ai_api_key")
        val AI_MODEL = stringPreferencesKey("ai_model")
        val ONBOARDED = intPreferencesKey("onboarding_complete")
        val REMIND_ENABLED = intPreferencesKey("remind_enabled")
        val REMIND_MORNING_H = intPreferencesKey("remind_morning_h")
        val REMIND_MORNING_M = intPreferencesKey("remind_morning_m")
        val REMIND_EVENING_H = intPreferencesKey("remind_evening_h")
        val REMIND_EVENING_M = intPreferencesKey("remind_evening_m")
    }

    // Total XP is derived from the completion ledger, not stored here.
    override val profile: Flow<UserProfile> = safeData.map { prefs ->
        UserProfile(
            preferences = UserPreferences(
                maxDailyQuests = prefs[Keys.MAX_DAILY] ?: 6,
                defaultAvailableMinutes = prefs[Keys.AVAILABLE_MIN] ?: 120,
                monthlyRewardBudgetCap = prefs[Keys.BUDGET_CAP] ?: 0.0,
                streakGraceDays = prefs[Keys.GRACE_DAYS] ?: 1,
                sensitiveNotificationsOptIn = (prefs[Keys.SENSITIVE_OPT_IN] ?: 0) == 1,
                focusCategories = (prefs[Keys.FOCUS] ?: emptySet())
                    .mapNotNull { runCatching { QuestCategory.valueOf(it) }.getOrNull() }
                    .toSet(),
            ),
            habits = decodeList(prefs[Keys.HABITS], Habit.serializer()),
            badHabits = decodeList(prefs[Keys.BAD_HABITS], BadHabit.serializer()),
            goals = decodeList(prefs[Keys.GOALS], Goal.serializer()),
        )
    }

    private fun <T> decodeList(raw: String?, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString(ListSerializer(serializer), raw) }.getOrDefault(emptyList())

    override suspend fun setBudgetCap(value: Double) {
        dataStore.edit { it[Keys.BUDGET_CAP] = value.coerceAtLeast(0.0) }
    }

    override suspend fun setMaxDaily(value: Int) {
        dataStore.edit { it[Keys.MAX_DAILY] = value.coerceIn(1, 20) }
    }

    override suspend fun setAvailableMinutes(value: Int) {
        dataStore.edit { it[Keys.AVAILABLE_MIN] = value.coerceIn(5, 1440) }
    }

    override suspend fun setFocusCategories(cats: Set<QuestCategory>) {
        dataStore.edit { it[Keys.FOCUS] = cats.map { c -> c.name }.toSet() }
    }

    override suspend fun setHabits(habits: List<Habit>) {
        dataStore.edit { it[Keys.HABITS] = json.encodeToString(ListSerializer(Habit.serializer()), habits) }
    }

    override suspend fun setBadHabits(badHabits: List<BadHabit>) {
        dataStore.edit {
            it[Keys.BAD_HABITS] = json.encodeToString(ListSerializer(BadHabit.serializer()), badHabits)
        }
    }

    override suspend fun setGoals(goals: List<Goal>) {
        dataStore.edit { it[Keys.GOALS] = json.encodeToString(ListSerializer(Goal.serializer()), goals) }
    }

    override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {
        dataStore.edit { prefs ->
            if (checkIn == null) {
                prefs.remove(Keys.CHECKIN_DAY)
                prefs.remove(Keys.CHECKIN_ENERGY)
                prefs.remove(Keys.CHECKIN_MIN)
            } else {
                prefs[Keys.CHECKIN_DAY] = checkIn.epochDay
                prefs[Keys.CHECKIN_ENERGY] = checkIn.energy
                prefs[Keys.CHECKIN_MIN] = checkIn.availableMinutes
            }
        }
    }

    override suspend fun getCheckIn(): EnergyCheckIn? {
        val prefs = safeData.first()
        val day = prefs[Keys.CHECKIN_DAY] ?: return null
        return EnergyCheckIn(
            epochDay = day,
            energy = prefs[Keys.CHECKIN_ENERGY] ?: 3,
            availableMinutes = prefs[Keys.CHECKIN_MIN] ?: 120,
        )
    }

    override suspend fun getAiConfig(): AiConfig {
        val prefs = safeData.first()
        var apiKey = keyStore.getApiKey()
        // One-time migration: move a legacy plaintext key from DataStore into the
        // secure key store, then scrub it from the plaintext prefs.
        val legacy = prefs[Keys.AI_KEY]
        if (apiKey.isBlank() && !legacy.isNullOrBlank()) {
            keyStore.setApiKey(legacy)
            dataStore.edit { it.remove(Keys.AI_KEY) }
            apiKey = legacy
        }
        return AiConfig(
            enabled = (prefs[Keys.AI_ENABLED] ?: 0) == 1,
            apiKey = apiKey,
            model = prefs[Keys.AI_MODEL] ?: AiConfig.DEFAULT_MODEL,
        )
    }

    override suspend fun setAiConfig(config: AiConfig) {
        keyStore.setApiKey(config.apiKey)
        dataStore.edit {
            it[Keys.AI_ENABLED] = if (config.enabled) 1 else 0
            it.remove(Keys.AI_KEY) // never persist the key in plaintext
            it[Keys.AI_MODEL] = config.model
        }
    }

    override suspend fun isOnboardingComplete(): Boolean =
        (safeData.first()[Keys.ONBOARDED] ?: 0) == 1

    override suspend fun setOnboardingComplete() {
        dataStore.edit { it[Keys.ONBOARDED] = 1 }
    }

    override suspend fun getReminderConfig(): ReminderConfig {
        val prefs = safeData.first()
        return ReminderConfig(
            enabled = (prefs[Keys.REMIND_ENABLED] ?: 0) == 1,
            morningHour = prefs[Keys.REMIND_MORNING_H] ?: 8,
            morningMinute = prefs[Keys.REMIND_MORNING_M] ?: 0,
            eveningHour = prefs[Keys.REMIND_EVENING_H] ?: 20,
            eveningMinute = prefs[Keys.REMIND_EVENING_M] ?: 0,
        )
    }

    override suspend fun setReminderConfig(config: ReminderConfig) {
        dataStore.edit {
            it[Keys.REMIND_ENABLED] = if (config.enabled) 1 else 0
            it[Keys.REMIND_MORNING_H] = config.morningHour
            it[Keys.REMIND_MORNING_M] = config.morningMinute
            it[Keys.REMIND_EVENING_H] = config.eveningHour
            it[Keys.REMIND_EVENING_M] = config.eveningMinute
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
        keyStore.clear()
    }
}
