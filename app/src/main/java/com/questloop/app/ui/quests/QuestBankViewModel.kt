package com.questloop.app.ui.quests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestBank
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
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
    /** Ids with an add in flight, so a fast double-tap can't re-fire. */
    val adding: Set<String> = emptySet(),
    val toast: String? = null,
    /** Bumped on every toast so identical consecutive messages still re-fire. */
    val toastId: Long = 0,
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
                val added = QuestBank.catalog.map { q -> q.id }.filter { id -> id in activeIds }.toSet()
                // Clear the in-flight marker only once the quest actually shows up
                // in the active set, so there's never a window where an id is in
                // neither `adding` nor `addedIds` (which would let a fast second
                // tap re-add it). See add() below.
                _state.update { it.copy(addedIds = added, adding = it.adding - added) }
            }
        }
    }

    fun add(quest: Quest) {
        val s = _state.value
        if (quest.id in s.addedIds || quest.id in s.adding) return
        _state.update { it.copy(adding = it.adding + quest.id) }
        viewModelScope.launch {
            repository.addFromBank(quest, AppClock.todayEpochDay())
            // Intentionally do NOT clear `adding` here — the quests collector
            // clears it when the new quest appears in addedIds, so the button
            // goes in-flight -> added with no gap a double-tap could exploit.
            _state.update { it.copy(toast = "Added \"${quest.title}\".", toastId = it.toastId + 1) }
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
