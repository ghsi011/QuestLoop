package com.questloop.app.ui.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestBank
import com.questloop.app.data.QuestRepository
import com.questloop.core.model.Quest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuestBankUiState(
    /** Catalog grouped by cadence for display. */
    val groups: List<BankGroup> = emptyList(),
    /** Ids of catalog quests already on the user's active list. */
    val addedIds: Set<String> = emptySet(),
    val toast: String? = null,
)

data class BankGroup(val title: String, val items: List<Quest>)

/** Backs the Quest Bank: a curated catalog the user can add to their own list. */
class QuestBankViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(QuestBankUiState(groups = grouped()))
    val state: StateFlow<QuestBankUiState> = _state.asStateFlow()

    init {
        // Track which catalog quests are already added so the UI reflects it live.
        viewModelScope.launch {
            repository.quests.collectLatest { active ->
                val activeIds = active.map { it.id }.toSet()
                _state.update { it.copy(addedIds = QuestBank.catalog.map { q -> q.id }.filter { id -> id in activeIds }.toSet()) }
            }
        }
    }

    fun add(quest: Quest) {
        viewModelScope.launch {
            repository.addFromBank(quest)
            _state.update { it.copy(toast = "Added \"${quest.title}\".") }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    private fun grouped(): List<BankGroup> {
        fun group(title: String, freq: com.questloop.core.model.QuestFrequency) =
            BankGroup(title, QuestBank.catalog.filter { it.frequency == freq })
        return listOf(
            group("Daily", com.questloop.core.model.QuestFrequency.DAILY),
            group("Weekly", com.questloop.core.model.QuestFrequency.WEEKLY),
            group("Monthly", com.questloop.core.model.QuestFrequency.MONTHLY),
            group("One-off", com.questloop.core.model.QuestFrequency.ONE_OFF),
        ).filter { it.items.isNotEmpty() }
    }
}
