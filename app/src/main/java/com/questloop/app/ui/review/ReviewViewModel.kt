package com.questloop.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.ai.AiNarrator
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
    /** Short human summary per period; fills in shortly after the cards (AI when on). */
    val weeklySummary: String? = null,
    val monthlySummary: String? = null,
)

class ReviewViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    // The review stats the AI summary was last written for, so re-entering the
    // screen doesn't fire fresh network calls when nothing changed.
    private var lastNarrationKey: String? = null

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val weekly = repository.review("This week", AppClock.startOfWeek(today), today)
            val monthly = repository.review("This month", AppClock.startOfMonth(today), today)
            // Seed the instant deterministic summary so the card never jumps; AI
            // refines it in place when it's on (keeps an existing AI line on reload).
            _state.update {
                it.copy(
                    loading = false,
                    weekly = weekly,
                    monthly = monthly,
                    weeklySummary = it.weeklySummary ?: AiNarrator.reviewFallback(weekly),
                    monthlySummary = it.monthlySummary ?: AiNarrator.reviewFallback(monthly),
                )
            }
            val key = signature(weekly) + "|" + signature(monthly)
            if (key != lastNarrationKey) {
                lastNarrationKey = key
                // Refresh the deterministic line for the current numbers, then narrate.
                _state.update {
                    it.copy(
                        weeklySummary = AiNarrator.reviewFallback(weekly),
                        monthlySummary = AiNarrator.reviewFallback(monthly),
                    )
                }
                val weeklySummary = repository.narrateReview(weekly).text
                val monthlySummary = repository.narrateReview(monthly).text
                _state.update { it.copy(weeklySummary = weeklySummary, monthlySummary = monthlySummary) }
            }
        }
    }

    private fun signature(r: ReviewGenerator.Review): String =
        "${r.totalCompleted}/${r.totalAttempted}/${r.activeDays}/${r.xpEarned}"
}
