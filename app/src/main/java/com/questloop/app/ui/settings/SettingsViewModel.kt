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
)

class SettingsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val prefs = repository.profile.first().preferences
            val ai = repository.aiConfig()
            val reminders = repository.reminderConfig()
            _state.update { it.copy(loading = false, prefs = prefs, ai = ai, reminders = reminders) }
        }
    }

    fun setReminders(config: ReminderConfig) = update { repository.setReminderConfig(config) }

    fun setAiEnabled(enabled: Boolean) = update { repository.setAiConfig(_state.value.ai.copy(enabled = enabled)) }
    fun setAiKey(key: String) = update { repository.setAiConfig(_state.value.ai.copy(apiKey = key.trim())) }
    fun setAiModel(model: String) = update { repository.setAiConfig(_state.value.ai.copy(model = model.trim())) }

    fun setMaxDaily(value: Int) = update { repository.setMaxDaily(value) }

    fun setAvailableMinutes(value: Int) = update { repository.setAvailableMinutes(value) }

    fun toggleFocus(category: QuestCategory) {
        val current = _state.value.prefs.focusCategories
        val next = if (category in current) current - category else current + category
        update { repository.setFocusCategories(next) }
    }

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

    private fun update(action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            load()
        }
    }
}
