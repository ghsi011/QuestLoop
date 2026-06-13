package com.questloop.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
}

/**
 * Stores lightweight profile + preference state (budget, settings) in DataStore.
 * Quests, completion history, and total XP live in Room; this keeps the simple
 * key/value state separate and reactive.
 */
class ProfileStore(private val context: Context) : ProfilePreferences {

    private object Keys {
        val MAX_DAILY = intPreferencesKey("max_daily_quests")
        val AVAILABLE_MIN = intPreferencesKey("default_available_minutes")
        val BUDGET_CAP = doublePreferencesKey("monthly_reward_budget_cap")
        val GRACE_DAYS = intPreferencesKey("streak_grace_days")
        val SENSITIVE_OPT_IN = intPreferencesKey("sensitive_opt_in")
        val FOCUS = stringSetPreferencesKey("focus_categories")
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
        )
    }

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
}
