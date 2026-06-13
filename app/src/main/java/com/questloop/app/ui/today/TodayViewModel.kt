package com.questloop.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.QuestLoopEngine
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.reward.LevelSystem
import com.questloop.core.safety.SafetyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val loading: Boolean = true,
    val plan: QuestGenerator.DailyPlan? = null,
    val totalXp: Long = 0,
    val level: Int = 1,
    val levelProgress: Double = 0.0,
    val signals: List<SafetyGuard.Signal> = emptyList(),
    val energy: Int? = null,
    val availableMinutes: Int? = null,
    val lastEffect: QuestLoopEngine.CompletionEffect? = null,
    val toast: String? = null,
)

class TodayViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.seedIfEmpty()
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val checkIn = _state.value.let { s ->
                if (s.energy != null || s.availableMinutes != null) {
                    EnergyCheckIn(
                        epochDay = today,
                        energy = s.energy ?: 3,
                        availableMinutes = s.availableMinutes ?: 120,
                    )
                } else null
            }
            val plan = repository.todayPlan(today, checkIn)
            val signals = repository.safetySignals(today)
            // Read XP from a one-shot profile snapshot.
            val xp = repository.profile.first().totalXp
            val progress = LevelSystem.progress(xp)
            _state.update {
                it.copy(
                    loading = false,
                    plan = plan,
                    totalXp = xp,
                    level = progress.level,
                    levelProgress = progress.fractionToNext,
                    signals = signals,
                )
            }
        }
    }

    fun setCheckIn(energy: Int, availableMinutes: Int) {
        _state.update { it.copy(energy = energy, availableMinutes = availableMinutes) }
        refresh()
    }

    fun complete(quest: Quest, result: CompletionResult) {
        viewModelScope.launch {
            val today = AppClock.todayEpochDay()
            val effect = repository.completeQuest(quest, today, result)
            val toast = if (effect.leveledUp) {
                "Level up! You reached level ${effect.newLevel}. ${effect.outcome.explanation}"
            } else {
                effect.outcome.explanation
            }
            _state.update { it.copy(lastEffect = effect, toast = toast) }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
}
