package com.questloop.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    suspend fun clear()
}

/**
 * Stores lightweight profile + preference state in DataStore: scalar settings as
 * typed prefs, and the habit/bad-habit/goal lists as JSON (the core models are
 * @Serializable). Quests, completion history, and total XP live in Room.
 */
class ProfileStore(private val context: Context) : ProfilePreferences {

    private val json = Json { ignoreUnknownKeys = true }

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
    }

    // Total XP is derived from the completion ledger, not stored here.
    override val profile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
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
        context.dataStore.edit { it[Keys.BUDGET_CAP] = value.coerceAtLeast(0.0) }
    }

    override suspend fun setMaxDaily(value: Int) {
        context.dataStore.edit { it[Keys.MAX_DAILY] = value.coerceIn(1, 20) }
    }

    override suspend fun setAvailableMinutes(value: Int) {
        context.dataStore.edit { it[Keys.AVAILABLE_MIN] = value.coerceIn(5, 1440) }
    }

    override suspend fun setFocusCategories(cats: Set<QuestCategory>) {
        context.dataStore.edit { it[Keys.FOCUS] = cats.map { c -> c.name }.toSet() }
    }

    override suspend fun setHabits(habits: List<Habit>) {
        context.dataStore.edit { it[Keys.HABITS] = json.encodeToString(ListSerializer(Habit.serializer()), habits) }
    }

    override suspend fun setBadHabits(badHabits: List<BadHabit>) {
        context.dataStore.edit {
            it[Keys.BAD_HABITS] = json.encodeToString(ListSerializer(BadHabit.serializer()), badHabits)
        }
    }

    override suspend fun setGoals(goals: List<Goal>) {
        context.dataStore.edit { it[Keys.GOALS] = json.encodeToString(ListSerializer(Goal.serializer()), goals) }
    }

    override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {
        context.dataStore.edit { prefs ->
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
        val prefs = context.dataStore.data.first()
        val day = prefs[Keys.CHECKIN_DAY] ?: return null
        return EnergyCheckIn(
            epochDay = day,
            energy = prefs[Keys.CHECKIN_ENERGY] ?: 3,
            availableMinutes = prefs[Keys.CHECKIN_MIN] ?: 120,
        )
    }

    override suspend fun getAiConfig(): AiConfig {
        val prefs = context.dataStore.data.first()
        return AiConfig(
            enabled = (prefs[Keys.AI_ENABLED] ?: 0) == 1,
            apiKey = prefs[Keys.AI_KEY].orEmpty(),
            model = prefs[Keys.AI_MODEL] ?: AiConfig.DEFAULT_MODEL,
        )
    }

    override suspend fun setAiConfig(config: AiConfig) {
        context.dataStore.edit {
            it[Keys.AI_ENABLED] = if (config.enabled) 1 else 0
            it[Keys.AI_KEY] = config.apiKey
            it[Keys.AI_MODEL] = config.model
        }
    }

    override suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
