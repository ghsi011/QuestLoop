package com.questloop.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.ai.AiNarrator
import com.questloop.core.generation.PeriodPlanner
import com.questloop.core.review.ReviewGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The Reviews tab shows either a retrospective (Review) or the forward plan (Plan). */
enum class ReviewMode { REVIEW, PLAN }

data class ReviewUiState(
    val loading: Boolean = true,
    val mode: ReviewMode = ReviewMode.REVIEW,
    val weekly: ReviewGenerator.Review? = null,
    val monthly: ReviewGenerator.Review? = null,
    /** Forward-looking plans for the current week/month (SPEC §4 weekly/monthly lists). */
    val weeklyPlan: PeriodPlanner.PeriodPlan? = null,
    val monthlyPlan: PeriodPlanner.PeriodPlan? = null,
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
            // Reviews look back (start-of-period → today); plans look forward
            // (today → end-of-period) over the same calendar week/month.
            val weekly = repository.review("This week", AppClock.startOfWeek(today), today)
            val monthly = repository.review("This month", AppClock.startOfMonth(today), today)
            val weeklyPlan = repository.periodPlan("This week", today, AppClock.endOfWeek(today))
            val monthlyPlan = repository.periodPlan("This month", today, AppClock.endOfMonth(today))
            // Always show the instant, factual summary; AI is opt-in via the button.
            _state.update {
                it.copy(
                    loading = false,
                    weekly = weekly,
                    monthly = monthly,
                    weeklyPlan = weeklyPlan,
                    monthlyPlan = monthlyPlan,
                    weeklySummary = AiNarrator.reviewFallback(weekly),
                    monthlySummary = AiNarrator.reviewFallback(monthly),
                    aiAvailable = repository.aiConfig().usable,
                )
            }
        }
    }

    fun setMode(mode: ReviewMode) {
        _state.update { it.copy(mode = mode) }
    }

    /** User-triggered: replace the factual summaries with AI-written ones. */
    fun summarizeWithAi() {
        val weekly = _state.value.weekly ?: return
        val monthly = _state.value.monthly ?: return
        if (_state.value.summarizing) return
        launchSafely {
            _state.update { it.copy(summarizing = true) }
            try {
                val weeklySummary = repository.narrateReview(weekly).text
                val monthlySummary = repository.narrateReview(monthly).text
                _state.update { it.copy(weeklySummary = weeklySummary, monthlySummary = monthlySummary) }
            } finally {
                // Always re-enable the button — a failure keeps the factual summaries.
                _state.update { it.copy(summarizing = false) }
            }
        }
    }
}
