package com.questloop.app.ui.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuestsUiState(
    val loading: Boolean = true,
    /** All user-managed quests with their status for today, grouped for display. */
    val groups: List<QuestGroup> = emptyList(),
    val total: Int = 0,
    val toast: String? = null,
)

/** A titled section of the backlog (e.g. "Today", "Scheduled", "Done today"). */
data class QuestGroup(val title: String, val items: List<QuestRepository.QuestStatus>)

/**
 * Backs the Quests screen: the full, transparent backlog of everything the user
 * created or accepted from AI, so nothing is hidden behind the curated daily plan.
 */
class QuestsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(QuestsUiState())
    val state: StateFlow<QuestsUiState> = _state.asStateFlow()

    init {
        // Recompute whenever the active quest set changes (e.g. AI quick-add adds
        // quests on another screen), so the backlog is always current.
        viewModelScope.launch {
            repository.quests.collectLatest { recompute() }
        }
    }

    fun refresh() {
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val today = AppClock.todayEpochDay()
        val overview = repository.questOverview(today, AppClock.currentDayPart())
        _state.update { it.copy(loading = false, groups = group(overview), total = overview.size) }
    }

    fun complete(quest: Quest) {
        viewModelScope.launch {
            repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.COMPLETED)
            _state.update { it.copy(toast = "Nice — marked done.") }
            recompute()
        }
    }

    fun delete(quest: Quest) {
        viewModelScope.launch {
            repository.archiveQuest(quest.id)
            _state.update { it.copy(toast = "Deleted \"${quest.title}\".") }
            // The quests flow will also emit; recompute now for immediate feedback.
            recompute()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * Groups the backlog into the order a to-do list reads best: what's on for
     * today, what else is due, what's scheduled for later, and what's already done.
     */
    private fun group(items: List<QuestRepository.QuestStatus>): List<QuestGroup> {
        val done = items.filter { it.completedToday }
        val todo = items.filterNot { it.completedToday }
        val inPlan = todo.filter { it.inTodaysPlan }
        val alsoDue = todo.filter { !it.inTodaysPlan && it.dueToday }
        val later = todo.filter { !it.inTodaysPlan && !it.dueToday }
        return buildList {
            if (inPlan.isNotEmpty()) add(QuestGroup("Today's plan", inPlan))
            if (alsoDue.isNotEmpty()) add(QuestGroup("Also due today", alsoDue))
            if (later.isNotEmpty()) add(QuestGroup("Scheduled for later", later))
            if (done.isNotEmpty()) add(QuestGroup("Done today", done))
        }
    }
}
