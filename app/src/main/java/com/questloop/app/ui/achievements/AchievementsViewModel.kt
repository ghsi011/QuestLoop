package com.questloop.app.ui.achievements

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AchievementsUiState(
    val loading: Boolean = true,
    val items: List<QuestRepository.AchievementStatus> = emptyList(),
    val unlockedCount: Int = 0,
    /** Set when loading fails, so the screen can offer a retry instead of spinning forever. */
    val error: String? = null,
)

class AchievementsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(AchievementsUiState())
    val state: StateFlow<AchievementsUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Public so the screen's error state can offer "Try again". */
    fun load() {
        launchSafely(onError = { t ->
            runCatching { Log.e("QuestLoop", "Achievements load failed", t) }
            _state.update { it.copy(loading = false, error = "Something went wrong.") }
        }) {
            _state.update { it.copy(loading = true, error = null) }
            val items = repository.achievementStatuses()
            _state.update {
                it.copy(loading = false, items = items, unlockedCount = items.count { s -> s.unlocked })
            }
        }
    }
}
