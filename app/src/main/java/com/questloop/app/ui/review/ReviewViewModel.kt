package com.questloop.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.review.ReviewGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val weekly: ReviewGenerator.Review? = null,
    val monthly: ReviewGenerator.Review? = null,
)

class ReviewViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val weekly = repository.review("This week", AppClock.startOfWeek(today), today)
            val monthly = repository.review("This month", AppClock.startOfMonth(today), today)
            _state.update { it.copy(loading = false, weekly = weekly, monthly = monthly) }
        }
    }
}
