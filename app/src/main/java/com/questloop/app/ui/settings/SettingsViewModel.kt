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
    /** Set when the AI error log is ready to be shared; consumed by the UI. */
    val diagnostics: String? = null,
    /** One-shot confirmation shown after a setting is saved; consumed by the UI. */
    val savedMessage: String? = null,
    /**
     * Monotonic id bumped on every [savedMessage] emit. The snackbar effect keys on
     * this, not the message string, so two identical confirmations (e.g. tapping the
     * same focus chip twice) both show instead of the second being swallowed.
     */
    val messageId: Long = 0,
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

    /**
     * Persists the whole AI config in one write (avoids racing partial copies) and
     * verifies the (encrypted) key actually persisted, so a silent Keystore write
     * failure surfaces to the user instead of falsely reporting success.
     */
    fun saveAi(enabled: Boolean, apiKey: String, model: String, filterWording: Boolean) {
        viewModelScope.launch {
            val trimmedKey = apiKey.trim()
            repository.setAiConfig(
                AiConfig(enabled = enabled, apiKey = trimmedKey, model = model.trim(), filterWording = filterWording),
            )
            reload()
            val persisted = _state.value.ai.apiKey == trimmedKey
            emitMessage(if (persisted) "AI settings saved" else "Couldn't save your key — please try again.")
        }
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

    /** Loads the AI error log to share; tells the user when there's nothing logged. */
    fun shareDiagnostics() {
        viewModelScope.launch {
            val dump = repository.aiDiagnosticsDump()
            if (dump.isBlank()) {
                emitMessage("No AI errors logged yet.")
            } else {
                _state.update { it.copy(diagnostics = dump) }
            }
        }
    }

    fun consumeDiagnostics() = _state.update { it.copy(diagnostics = null) }

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
            emitMessage(message)
        }
    }

    /** Sets a one-shot confirmation and bumps [SettingsUiState.messageId] so the UI shows it. */
    private fun emitMessage(message: String) =
        _state.update { it.copy(savedMessage = message, messageId = it.messageId + 1) }
}
