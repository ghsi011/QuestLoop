package com.questloop.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.AiConfig
import com.questloop.app.data.AiProvider
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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
    /** Set to the browser URL the user must open to finish ChatGPT sign-in; consumed by the UI. */
    val openAuthUrl: String? = null,
    /** Monotonic id bumped per [openAuthUrl] emit so the launch effect isn't swallowed. */
    val authId: Long = 0,
    /** True while a ChatGPT sign-in is in flight (disables the button). */
    val aiBusy: Boolean = false,
)

class SettingsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        launchSafely {
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
        launchSafely {
            val trimmedKey = apiKey.trim()
            // Copy onto the current config so provider/OpenAI fields aren't wiped.
            val current = repository.aiConfig()
            // setAiConfig can now throw if the secure key store rejects the write
            // (corrupt/unavailable Keystore); catch it so we report the failure
            // instead of crashing or falsely claiming success.
            val wrote = runCatching {
                repository.setAiConfig(
                    current.copy(
                        enabled = enabled,
                        provider = AiProvider.OPENROUTER,
                        apiKey = trimmedKey,
                        model = model.trim(),
                        filterWording = filterWording,
                    ),
                )
            }.isSuccess
            reload()
            val persisted = wrote && _state.value.ai.apiKey == trimmedKey
            emitMessage(if (persisted) "AI settings saved" else "Couldn't save your key — please try again.")
        }
    }

    /** Switches the active AI backend, preserving each provider's saved credentials. */
    fun setProvider(provider: AiProvider) {
        launchSafely {
            val current = repository.aiConfig()
            if (current.provider == provider) return@launchSafely
            repository.setAiConfig(current.copy(provider = provider))
            reload()
        }
    }

    /** Saves the OpenAI-specific settings (preserving the linked account + OpenRouter key). */
    fun saveOpenAi(enabled: Boolean, model: String, filterWording: Boolean) {
        launchSafely {
            val current = repository.aiConfig()
            runCatching {
                repository.setAiConfig(
                    current.copy(
                        enabled = enabled,
                        provider = AiProvider.OPENAI,
                        openAiModel = model.trim(),
                        filterWording = filterWording,
                    ),
                )
            }
            reload()
            emitMessage("AI settings saved")
        }
    }

    /**
     * Starts the "Sign in with ChatGPT" OAuth flow. The repository hands back the
     * browser URL via the callback (surfaced as [SettingsUiState.openAuthUrl] for
     * the screen to open) and suspends until the loopback callback completes.
     */
    fun connectOpenAi() {
        launchSafely {
            _state.update { it.copy(aiBusy = true) }
            val result = repository.connectOpenAi { url ->
                _state.update { it.copy(openAuthUrl = url, authId = it.authId + 1) }
            }
            reload()
            _state.update { it.copy(aiBusy = false) }
            emitMessage(
                result.fold(
                    onSuccess = { "Connected to ChatGPT" },
                    onFailure = { "Couldn't connect: ${it.message ?: "please try again."}" },
                ),
            )
        }
    }

    fun disconnectOpenAi() {
        launchSafely {
            repository.disconnectOpenAi()
            reload()
            emitMessage("Disconnected from ChatGPT")
        }
    }

    fun consumeOpenAuthUrl() = _state.update { it.copy(openAuthUrl = null) }

    fun setMaxDaily(value: Int) = update("Saved · up to $value quests a day") { repository.setMaxDaily(value) }

    fun setAvailableMinutes(value: Int) = update("Saved · ${value}m a day") { repository.setAvailableMinutes(value) }

    fun toggleFocus(category: QuestCategory) {
        val current = _state.value.prefs.focusCategories
        val next = if (category in current) current - category else current + category
        update("Focus areas updated") { repository.setFocusCategories(next) }
    }

    fun consumeSavedMessage() = _state.update { it.copy(savedMessage = null) }

    fun requestExport() {
        launchSafely {
            val json = repository.exportJson()
            _state.update { it.copy(exportJson = json) }
        }
    }

    fun consumeExport() = _state.update { it.copy(exportJson = null) }

    /** Restores a backup the user picked; reports counts or a plain error. */
    fun importData(json: String) {
        launchSafely {
            val result = repository.importJson(json)
            reload()
            emitMessage(
                result.error
                    ?: "Imported ${result.quests} quests and ${result.completions} entries.",
            )
        }
    }

    fun reportImportError() = emitMessage("Couldn't read that file.")

    /** Loads the AI error log to share; tells the user when there's nothing logged. */
    fun shareDiagnostics() {
        launchSafely {
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
        launchSafely {
            repository.deleteAllData()
            // Also drop the process-scoped Add-screen draft so a half-typed quest
            // doesn't survive an explicit "delete everything".
            com.questloop.app.ui.add.AddQuestViewModel.resetDraftCache()
            reload()
            emitMessage("Your data has been deleted.")
            onDone()
        }
    }

    private fun update(message: String, action: suspend () -> Unit) {
        launchSafely {
            action()
            reload()
            emitMessage(message)
        }
    }

    /** Sets a one-shot confirmation and bumps [SettingsUiState.messageId] so the UI shows it. */
    private fun emitMessage(message: String) =
        _state.update { it.copy(savedMessage = message, messageId = it.messageId + 1) }
}
