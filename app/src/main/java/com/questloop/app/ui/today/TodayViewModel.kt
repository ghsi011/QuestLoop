package com.questloop.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.QuestLoopEngine
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.reward.Achievement
import com.questloop.core.reward.LevelSystem
import com.questloop.core.safety.SafetyGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    val loading: Boolean = true,
    val plan: QuestGenerator.DailyPlan? = null,
    val totalXp: Long = 0,
    val level: Int = 1,
    val levelProgress: Double = 0.0,
    val streak: Int = 0,
    val signals: List<SafetyGuard.Signal> = emptyList(),
    val achievements: List<Achievement> = emptyList(),
    val energy: Int? = null,
    val availableMinutes: Int? = null,
    /** Today's logged progress per quest id (count or minutes) for non-binary quests. */
    val todayProgress: Map<String, Int> = emptyMap(),
    val lastEffect: QuestLoopEngine.CompletionEffect? = null,
    val toast: String? = null,
    val pendingUndo: PendingUndo? = null,
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
            // Restore today's persisted check-in so the plan and chips survive
            // navigation and process death.
            val checkIn = repository.todayCheckIn(today)
            val plan = repository.todayPlan(today, AppClock.currentDayPart(), checkIn)
            val signals = repository.safetySignals(today)
            val achievements = repository.unlockedAchievements()
            val todayProgress = repository.todayProgress(today)
            // Total XP is sourced from the completion ledger.
            val xp = repository.totalXp()
            val progress = LevelSystem.progress(xp)
            val streak = repository.currentStreak(today)
            _state.update {
                it.copy(
                    loading = false,
                    plan = plan,
                    totalXp = xp,
                    level = progress.level,
                    levelProgress = progress.fractionToNext,
                    streak = streak,
                    signals = signals,
                    achievements = achievements,
                    todayProgress = todayProgress,
                    energy = checkIn?.energy,
                    availableMinutes = checkIn?.availableMinutes,
                )
            }
        }
    }

    fun setCheckIn(energy: Int, availableMinutes: Int) {
        viewModelScope.launch {
            repository.setCheckIn(EnergyCheckIn(AppClock.todayEpochDay(), energy, availableMinutes))
            refresh()
        }
    }

    fun complete(quest: Quest, result: CompletionResult) {
        viewModelScope.launch { applyOutcome(repository.completeQuest(quest, AppClock.todayEpochDay(), result)) }
    }

    /** Completes a non-binary quest from a measured value (count/minutes/rating). */
    fun completeMeasured(quest: Quest, value: Int) {
        viewModelScope.launch { applyOutcome(repository.completeMeasured(quest, AppClock.todayEpochDay(), value)) }
    }

    private fun applyOutcome(outcome: QuestRepository.CompleteOutcome) {
        val effect = outcome.effect
        val toast = buildString {
            if (effect.leveledUp) append("Level up! You reached level ${effect.newLevel}. ")
            append(effect.outcome.explanation)
            outcome.newlyUnlocked.firstOrNull()?.let { append(" 🏆 Achievement unlocked: ${it.title}!") }
        }
        _state.update {
            it.copy(
                lastEffect = effect,
                toast = toast,
                pendingUndo = PendingUndo(outcome.instanceId, outcome.previousRecord),
            )
        }
        refresh()
    }

    fun undoLast() {
        val undo = _state.value.pendingUndo ?: return
        viewModelScope.launch {
            repository.undoCompletion(undo.instanceId, undo.previous)
            _state.update { it.copy(toast = null, pendingUndo = null) }
            refresh()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null, pendingUndo = null) }
}

/** Data needed to reverse the last completion via the snackbar "Undo" action. */
data class PendingUndo(val instanceId: String, val previous: CompletionRecord?)
