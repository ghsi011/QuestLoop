package com.questloop.app.ui.rewards

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.app.ui.Money
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import com.questloop.core.reward.CompletionSound
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
    /** True while a "Mark done" completion is in flight, to guard against double-taps. */
    val fundStepInFlight: Boolean = false,
    /** One-shot snackbar message (a confirmation, or a load error); consumed by the UI. */
    val savedMessage: String? = null,
    /**
     * Monotonic id bumped on every [savedMessage] emit. The snackbar effect keys on
     * this, not the message string, so two identical confirmations (e.g. saving the
     * same budget twice) both show instead of the second being swallowed.
     */
    val messageId: Long = 0,
    /** One-shot celebration chime for a just-completed fund step; consumed by the UI. */
    val sound: CompletionSound? = null,
)

class RewardsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(RewardsUiState())
    val state: StateFlow<RewardsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        launchSafely(onError = ::loadFailed) {
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

    /** A failed load must not silently render a zeroed budget — say so instead. */
    private fun loadFailed(t: Throwable) {
        runCatching { Log.e("QuestLoop", "Rewards load failed", t) }
        _state.update {
            it.copy(
                loading = false,
                savedMessage = "Couldn't load your rewards — please try again.",
                messageId = it.messageId + 1,
                sound = null,
            )
        }
    }

    fun setBudgetCap(value: Double) {
        launchSafely {
            repository.setBudgetCap(value)
            load()
            val msg = if (value > 0) "Budget saved: ${Money.format(value)}." else "Budget cleared."
            _state.update { it.copy(savedMessage = msg, messageId = it.messageId + 1, sound = null) }
        }
    }

    /** Marks a reward-fund admin step done (the user did the external action).
     *  Guarded so a double-tap can't fire two completions or duplicate snackbars. */
    fun markFundStepDone(quest: Quest) {
        if (_state.value.fundStepInFlight) return
        _state.update { it.copy(fundStepInFlight = true) }
        launchSafely {
            try {
                val outcome = repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.COMPLETED)
                load()
                _state.update {
                    it.copy(savedMessage = "Marked done — nice.", messageId = it.messageId + 1, sound = outcome.sound)
                }
            } finally {
                _state.update { it.copy(fundStepInFlight = false) }
            }
        }
    }

    fun consumeSavedMessage() = _state.update { it.copy(savedMessage = null) }

    fun consumeSound() = _state.update { it.copy(sound = null) }
}
