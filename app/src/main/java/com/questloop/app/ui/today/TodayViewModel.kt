package com.questloop.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.QuestLoopEngine
import com.questloop.core.ai.AiNarrator
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.reward.Achievement
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
    val streak: Int = 0,
    val signals: List<SafetyGuard.Signal> = emptyList(),
    val achievements: List<Achievement> = emptyList(),
    val energy: Int? = null,
    /** Estimated minutes in today's plan vs. the day's available time (energy budget). */
    val plannedMinutes: Int = 0,
    val availableMinutes: Int = 0,
    /** One line on why today's plan has this shape (AI when on, else terse). */
    val planRationale: String? = null,
    /** Today's logged progress per quest id (count or minutes) for non-binary quests. */
    val todayProgress: Map<String, Int> = emptyMap(),
    val lastEffect: QuestLoopEngine.CompletionEffect? = null,
    val toast: String? = null,
    /** Bumped on every toast so identical consecutive messages still re-fire. */
    val toastId: Long = 0,
    val pendingUndo: PendingUndo? = null,
    /** True while a completion is being recorded; guards double-taps. */
    val completing: Boolean = false,
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
            // The time budget is only real when the user did an energy check-in; otherwise
            // available stays 0 and the budget bar is hidden (don't assert a guessed limit).
            val available = checkIn?.availableMinutes ?: 0
            // Minutes still to do: discount logged time on partially-done timed quests so
            // the bar doesn't overstate remaining load.
            val planned = plan.quests.sumOf { qi ->
                val q = qi.quest
                if (q.completionStyle == CompletionStyle.DURATION) {
                    (q.estimatedMinutes - (todayProgress[q.id] ?: 0)).coerceAtLeast(0)
                } else {
                    q.estimatedMinutes
                }
            }
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
                    plannedMinutes = planned,
                    availableMinutes = available,
                    // Deterministic, instant line — AI is reserved for the user-triggered
                    // review summary, so Today never makes a silent network call.
                    planRationale = if (plan.quests.isEmpty()) {
                        null
                    } else {
                        AiNarrator.planFallback(AiNarrator.PlanFacts.from(plan, checkIn, available, today))
                    },
                )
            }
        }
    }

    /** Permanently removes a first-run guide quest (no XP, no penalty). */
    fun dismissGuide(quest: Quest) {
        viewModelScope.launch {
            repository.archiveQuest(quest.id)
            refresh()
        }
    }

    fun setCheckIn(energy: Int) {
        viewModelScope.launch {
            // Energy only. The time budget comes from the user's Settings
            // "Time/day" (defaultAvailableMinutes) so picking an energy level
            // never silently overrides the configured budget.
            val minutes = repository.profile.first().preferences.defaultAvailableMinutes
            repository.setCheckIn(EnergyCheckIn(AppClock.todayEpochDay(), energy, minutes))
            refresh()
        }
    }

    fun complete(quest: Quest, result: CompletionResult) = runCompletion {
        repository.completeQuest(quest, AppClock.todayEpochDay(), result)
    }

    /** Completes a non-binary quest from a measured value (count/minutes/rating). */
    fun completeMeasured(quest: Quest, value: Int) = runCompletion {
        repository.completeMeasured(quest, AppClock.todayEpochDay(), value)
    }

    /** Serializes completions so a double-tap can't fire twice or clobber the Undo. */
    private fun runCompletion(block: suspend () -> QuestRepository.CompleteOutcome) {
        if (_state.value.completing) return
        _state.update { it.copy(completing = true) }
        viewModelScope.launch {
            try {
                applyOutcome(block())
            } finally {
                _state.update { it.copy(completing = false) }
            }
        }
    }

    private fun applyOutcome(outcome: QuestRepository.CompleteOutcome) {
        val effect = outcome.effect
        val toast = buildString {
            if (effect.leveledUp) append("Level ${effect.newLevel} reached. ")
            append(effect.outcome.explanation)
            outcome.newlyUnlocked.firstOrNull()?.let { append("  🏆 ${it.title}") }
        }
        _state.update {
            it.copy(
                lastEffect = effect,
                toast = toast,
                toastId = it.toastId + 1,
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
