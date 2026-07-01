package com.questloop.app.ui.completed

import androidx.lifecycle.ViewModel
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.app.util.launchSafely
import com.questloop.core.model.Quest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Time window for the completed-quest history. */
enum class HistoryFilter(val label: String) {
    TODAY("Today"),
    WEEK("This week"),
    MONTH("This month"),
    ALL("All time"),
}

/** The quest + completion currently open in the edit dialog. */
data class EditTarget(val quest: Quest, val instanceId: String)

data class CompletedUiState(
    val loading: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.WEEK,
    val entries: List<QuestRepository.CompletedEntry> = emptyList(),
    val editing: EditTarget? = null,
    /** One-shot confirmation (undo/edit/re-add); consumed by the UI. */
    val message: String? = null,
    val messageId: Long = 0,
)

/**
 * Backs the Completed-quests screen: a filtered history with undo, a full-quest
 * edit (which re-scores XP), and re-add (clone as a new quest).
 */
class CompletedViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(CompletedUiState())
    val state: StateFlow<CompletedUiState> = _state.asStateFlow()

    init { load() }

    fun setFilter(filter: HistoryFilter) {
        _state.update { it.copy(filter = filter) }
        load()
    }

    fun load() {
        launchSafely {
            _state.update { it.copy(loading = true) }
            val entries = repository.completedHistory(rangeFor(_state.value.filter))
            _state.update { it.copy(loading = false, entries = entries) }
        }
    }

    /** Undo: remove the completion; XP re-derives from the ledger. */
    fun undo(entry: QuestRepository.CompletedEntry) {
        launchSafely {
            repository.deleteCompletion(entry.record.instanceId)
            load()
            emit("Removed \"${entry.title}\" — XP updated.")
        }
    }

    fun startEdit(entry: QuestRepository.CompletedEntry) {
        val quest = entry.quest ?: return // can't edit a quest that no longer exists
        _state.update { it.copy(editing = EditTarget(quest, entry.record.instanceId)) }
    }

    fun cancelEdit() = _state.update { it.copy(editing = null) }

    /** Save the edited quest and re-score the clicked completion with it. */
    fun saveEdit(updated: Quest) {
        val target = _state.value.editing ?: return
        launchSafely {
            repository.editQuestAndRescore(updated, target.instanceId)
            _state.update { it.copy(editing = null) }
            load()
            emit("Updated \"${updated.title}\".")
        }
    }

    /** Re-add: clone the quest definition as a fresh active quest. */
    fun readd(entry: QuestRepository.CompletedEntry) {
        val quest = entry.quest ?: return
        launchSafely {
            repository.readdQuest(quest)
            emit("Added a new \"${quest.title}\".")
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun rangeFor(filter: HistoryFilter): LongRange? {
        val today = AppClock.todayEpochDay()
        return when (filter) {
            HistoryFilter.TODAY -> today..today
            HistoryFilter.WEEK -> AppClock.startOfWeek(today)..today
            HistoryFilter.MONTH -> AppClock.startOfMonth(today)..today
            HistoryFilter.ALL -> null
        }
    }

    private fun emit(message: String) =
        _state.update { it.copy(message = message, messageId = it.messageId + 1) }
}
