package com.questloop.app.ui.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AchievementsUiState(
    val loading: Boolean = true,
    val items: List<QuestRepository.AchievementStatus> = emptyList(),
    val unlockedCount: Int = 0,
)

class AchievementsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(AchievementsUiState())
    val state: StateFlow<AchievementsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val items = repository.achievementStatuses()
            _state.update {
                it.copy(loading = false, items = items, unlockedCount = items.count { s -> s.unlocked })
            }
        }
    }
}
