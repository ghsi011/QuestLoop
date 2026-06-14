package com.questloop.app.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.core.model.BadHabit
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class HabitsUiState(
    val loading: Boolean = true,
    val habits: List<Habit> = emptyList(),
    val badHabits: List<BadHabit> = emptyList(),
)

class HabitsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(HabitsUiState())
    val state: StateFlow<HabitsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val profile = repository.profile.first()
            _state.update { it.copy(loading = false, habits = profile.habits, badHabits = profile.badHabits) }
        }
    }

    fun addHabit(title: String, category: QuestCategory, targetPerWeek: Int) {
        if (title.isBlank()) return
        mutate {
            repository.addHabit(
                Habit(
                    id = UUID.randomUUID().toString(),
                    title = title.trim(),
                    category = category,
                    difficulty = Difficulty.EASY,
                    targetPerWeek = targetPerWeek.coerceIn(1, 7),
                ),
            )
        }
    }

    fun removeHabit(id: String) = mutate { repository.removeHabit(id) }

    fun addBadHabit(title: String, dailyLimit: Int?) {
        if (title.isBlank()) return
        mutate {
            repository.addBadHabit(
                BadHabit(id = UUID.randomUUID().toString(), title = title.trim(), dailyLimit = dailyLimit),
            )
        }
    }

    fun removeBadHabit(id: String) = mutate { repository.removeBadHabit(id) }

    private fun mutate(action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            load()
        }
    }
}
