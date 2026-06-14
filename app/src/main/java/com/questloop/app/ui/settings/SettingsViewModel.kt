package com.questloop.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
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
)

class SettingsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val prefs = repository.profile.first().preferences
            _state.update { it.copy(loading = false, prefs = prefs) }
        }
    }

    fun setMaxDaily(value: Int) = update { repository.setMaxDaily(value) }

    fun setAvailableMinutes(value: Int) = update { repository.setAvailableMinutes(value) }

    fun toggleFocus(category: QuestCategory) {
        val current = _state.value.prefs.focusCategories
        val next = if (category in current) current - category else current + category
        update { repository.setFocusCategories(next) }
    }

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
