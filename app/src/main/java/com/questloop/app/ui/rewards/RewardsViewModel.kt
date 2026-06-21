package com.questloop.app.ui.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.app.ui.Money
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import com.questloop.core.reward.RewardAllowanceCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

data class RewardsUiState(
    val loading: Boolean = true,
    val budgetCap: Double = 0.0,
    val allowance: RewardAllowanceCalculator.AllowanceResult? = null,
    /** Reward-fund admin flow status (SPEC §6): whether the pot is set up + the next steps. */
    val fundBudgetSet: Boolean = false,
    val fundPotOpened: Boolean = false,
    val fundSteps: List<Quest> = emptyList(),
    /** One-shot confirmation shown after the budget is saved; consumed by the UI. */
    val savedMessage: String? = null,
    /**
     * Monotonic id bumped on every [savedMessage] emit. The snackbar effect keys on
     * this, not the message string, so two identical confirmations (e.g. saving the
     * same budget twice) both show instead of the second being swallowed.
     */
    val messageId: Long = 0,
)

class RewardsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(RewardsUiState())
    val state: StateFlow<RewardsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        launchSafely {
            _state.update { it.copy(loading = true) }
            val today = AppClock.todayEpochDay()
            val cap = repository.profile.first().preferences.monthlyRewardBudgetCap
            val allowance = repository.allowance(AppClock.startOfMonth(today), today)
            val fund = repository.rewardFundStatus(today)
            _state.update {
                it.copy(
                    loading = false,
                    budgetCap = cap,
                    allowance = allowance,
                    fundBudgetSet = fund.budgetSet,
                    fundPotOpened = fund.potOpened,
                    fundSteps = fund.steps,
                )
            }
        }
    }

    fun setBudgetCap(value: Double) {
        launchSafely {
            repository.setBudgetCap(value)
            load()
            val msg = if (value > 0) "Budget saved: ${Money.format(value)}." else "Budget cleared."
            _state.update { it.copy(savedMessage = msg, messageId = it.messageId + 1) }
        }
    }

    /** Marks a reward-fund admin step done (the user did the external action). */
    fun markFundStepDone(quest: Quest) {
        launchSafely {
            repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.COMPLETED)
            load()
            _state.update { it.copy(savedMessage = "Marked done — nice.", messageId = it.messageId + 1) }
        }
    }

    fun consumeSavedMessage() = _state.update { it.copy(savedMessage = null) }
}
