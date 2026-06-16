package com.questloop.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.AiConfig
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val prefs: UserPreferences = UserPreferences(),
    val ai: AiConfig = AiConfig(),
    val reminders: ReminderConfig = ReminderConfig(),
    /** Set when an export is ready to be shared; consumed by the UI. */
    val exportJson: String? = null,
    /** One-shot confirmation shown after a setting is saved; consumed by the UI. */
    val savedMessage: String? = null,
)

class SettingsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            reload()
        }
    }

    private suspend fun reload() {
        val prefs = repository.profile.first().preferences
        val ai = repository.aiConfig()
        val reminders = repository.reminderConfig()
        _state.update { it.copy(loading = false, prefs = prefs, ai = ai, reminders = reminders) }
    }

    fun setReminders(config: ReminderConfig) =
        update(if (config.enabled) "Reminders on" else "Reminders off") { repository.setReminderConfig(config) }

    /** Persists the whole AI config in one write (avoids racing partial copies). */
    fun saveAi(enabled: Boolean, apiKey: String, model: String) = update("AI settings saved") {
        repository.setAiConfig(AiConfig(enabled = enabled, apiKey = apiKey.trim(), model = model.trim()))
    }

    fun setMaxDaily(value: Int) = update("Saved · up to $value quests a day") { repository.setMaxDaily(value) }

    fun setAvailableMinutes(value: Int) = update("Saved · ${value}m a day") { repository.setAvailableMinutes(value) }

    fun toggleFocus(category: QuestCategory) {
        val current = _state.value.prefs.focusCategories
        val next = if (category in current) current - category else current + category
        update("Focus areas updated") { repository.setFocusCategories(next) }
    }

    fun consumeSavedMessage() = _state.update { it.copy(savedMessage = null) }

    fun requestExport() {
        viewModelScope.launch {
            val json = repository.exportJson()
            _state.update { it.copy(exportJson = json) }
        }
    }

    fun consumeExport() = _state.update { it.copy(exportJson = null) }

    fun deleteAllData(onDone: () -> Unit) {
        viewModelScope.launch {
            repository.deleteAllData()
            load()
            onDone()
        }
    }

    private fun update(message: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            reload()
            _state.update { it.copy(savedMessage = message) }
        }
    }
}
