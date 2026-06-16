package com.questloop.app.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.core.reward.RewardAllowanceCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RewardsUiState(
    val loading: Boolean = true,
    val budgetCap: Double = 0.0,
    val allowance: RewardAllowanceCalculator.AllowanceResult? = null,
    /** One-shot confirmation shown after the budget is saved; consumed by the UI. */
    val savedMessage: String? = null,
)

class RewardsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(RewardsUiState())
    val state: StateFlow<RewardsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val cap = repository.profile.first().preferences.monthlyRewardBudgetCap
            val allowance = repository.allowance(AppClock.startOfMonth(today), today)
            _state.update { it.copy(loading = false, budgetCap = cap, allowance = allowance) }
        }
    }

    fun setBudgetCap(value: Double) {
        viewModelScope.launch {
            repository.setBudgetCap(value)
            load()
            val msg = if (value > 0) "Budget saved: ${"%.2f".format(value)}." else "Budget cleared."
            _state.update { it.copy(savedMessage = msg) }
        }
    }

    fun consumeSavedMessage() = _state.update { it.copy(savedMessage = null) }
}
