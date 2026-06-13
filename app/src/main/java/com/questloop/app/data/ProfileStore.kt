package com.questloop.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "questloop_profile")

/**
 * Stores lightweight profile + preference state (XP, budget, settings) in
 * DataStore. Quests and completion history live in Room; this keeps the simple
 * key/value state separate and reactive.
 */
class ProfileStore(private val context: Context) {

    private object Keys {
        val TOTAL_XP = longPreferencesKey("total_xp")
        val MAX_DAILY = intPreferencesKey("max_daily_quests")
        val AVAILABLE_MIN = intPreferencesKey("default_available_minutes")
        val BUDGET_CAP = doublePreferencesKey("monthly_reward_budget_cap")
        val GRACE_DAYS = intPreferencesKey("streak_grace_days")
        val SENSITIVE_OPT_IN = intPreferencesKey("sensitive_opt_in")
        val FOCUS = stringSetPreferencesKey("focus_categories")
    }

    val profile: Flow<UserProfile> = context.dataStore.data.map { prefs ->
        UserProfile(
            totalXp = prefs[Keys.TOTAL_XP] ?: 0L,
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

    suspend fun setTotalXp(value: Long) = context.dataStore.edit { it[Keys.TOTAL_XP] = value }

    suspend fun setBudgetCap(value: Double) =
        context.dataStore.edit { it[Keys.BUDGET_CAP] = value.coerceAtLeast(0.0) }

    suspend fun setMaxDaily(value: Int) =
        context.dataStore.edit { it[Keys.MAX_DAILY] = value.coerceIn(1, 20) }

    suspend fun setAvailableMinutes(value: Int) =
        context.dataStore.edit { it[Keys.AVAILABLE_MIN] = value.coerceIn(5, 1440) }

    suspend fun setFocusCategories(cats: Set<QuestCategory>) =
        context.dataStore.edit { it[Keys.FOCUS] = cats.map { c -> c.name }.toSet() }
}
