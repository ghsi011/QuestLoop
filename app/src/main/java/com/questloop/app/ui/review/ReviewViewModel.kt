package com.questloop.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.ai.AiNarrator
import com.questloop.core.review.ReviewGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReviewUiState(
    val loading: Boolean = true,
    val weekly: ReviewGenerator.Review? = null,
    val monthly: ReviewGenerator.Review? = null,
    /** Per-period summary: a terse deterministic line by default, replaced by AI on request. */
    val weeklySummary: String? = null,
    val monthlySummary: String? = null,
    /** Whether the "Summarize with AI" action is available (a key is configured). */
    val aiAvailable: Boolean = false,
    /** True while the user-triggered AI summary is in flight. */
    val summarizing: Boolean = false,
)

class ReviewViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    fun load() {
        launchSafely {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val weekly = repository.review("This week", AppClock.startOfWeek(today), today)
            val monthly = repository.review("This month", AppClock.startOfMonth(today), today)
            // Always show the instant, factual summary; AI is opt-in via the button.
            _state.update {
                it.copy(
                    loading = false,
                    weekly = weekly,
                    monthly = monthly,
                    weeklySummary = AiNarrator.reviewFallback(weekly),
                    monthlySummary = AiNarrator.reviewFallback(monthly),
                    aiAvailable = repository.aiConfig().usable,
                )
            }
        }
    }

    /** User-triggered: replace the factual summaries with AI-written ones. */
    fun summarizeWithAi() {
        val weekly = _state.value.weekly ?: return
        val monthly = _state.value.monthly ?: return
        if (_state.value.summarizing) return
        launchSafely {
            _state.update { it.copy(summarizing = true) }
            val weeklySummary = repository.narrateReview(weekly).text
            val monthlySummary = repository.narrateReview(monthly).text
            _state.update {
                it.copy(summarizing = false, weeklySummary = weeklySummary, monthlySummary = monthlySummary)
            }
        }
    }
}
