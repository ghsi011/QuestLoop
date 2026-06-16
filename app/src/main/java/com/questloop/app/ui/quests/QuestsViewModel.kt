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
    /** All not-yet-done quests with their status for today, grouped for display. */
    val groups: List<QuestGroup> = emptyList(),
    val total: Int = 0,
    val toast: String? = null,
)

/** A titled section of the backlog (e.g. "Today's plan", "Scheduled for later"). */
data class QuestGroup(val title: String, val items: List<QuestRepository.QuestStatus>)

/**
 * Backs the Quests screen: the full, transparent backlog of everything the user
 * created or accepted from AI. Quests are completed with their real controls
 * (counting/timed/subjective/reduction) and disappear once done for the day.
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

    fun complete(quest: Quest) = act("Nice — done. ✨") {
        repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.COMPLETED)
    }

    fun skip(quest: Quest) = act(if (quest.isReductionQuest) "Logged honestly." else "Skipped for today.") {
        repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.SKIPPED)
    }

    fun completeMeasured(quest: Quest, value: Int) = act("Progress logged. ✨") {
        repository.completeMeasured(quest, AppClock.todayEpochDay(), value)
    }

    fun delete(quest: Quest) = act("Deleted \"${quest.title}\".") {
        repository.archiveQuest(quest.id)
    }

    private fun act(toast: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            _state.update { it.copy(toast = toast) }
            recompute()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * Groups the not-yet-done backlog: what's on for today, what else is due, and
     * what's scheduled for later. Done quests drop out entirely (they reappear
     * tomorrow if they recur).
     */
    private fun group(items: List<QuestRepository.QuestStatus>): List<QuestGroup> {
        val open = items.filterNot { it.done }
        val inPlan = open.filter { it.inTodaysPlan }
        val alsoDue = open.filter { !it.inTodaysPlan && it.dueToday }
        val later = open.filter { !it.inTodaysPlan && !it.dueToday }
        return buildList {
            if (inPlan.isNotEmpty()) add(QuestGroup("Today's plan", inPlan))
            if (alsoDue.isNotEmpty()) add(QuestGroup("Also due today", alsoDue))
            if (later.isNotEmpty()) add(QuestGroup("Scheduled for later", later))
        }
    }
}
