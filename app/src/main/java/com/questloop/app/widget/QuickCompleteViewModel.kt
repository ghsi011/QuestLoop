package com.questloop.app.widget

import androidx.lifecycle.ViewModel
import com.questloop.app.data.QuestRepository
import com.questloop.app.util.launchSafely
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QuickCompleteUiState(
    /** True until the quest has been looked up. */
    val loading: Boolean = true,
    /** The quest to act on couldn't be found (already done elsewhere, or archived). */
    val notFound: Boolean = false,
    val title: String = "",
    /** True while a complete/skip is in flight (guards double-tap). */
    val submitting: Boolean = false,
    /** Inline error that keeps the menu open so the user can retry. */
    val error: String? = null,
    /** Set once the action lands; the host activity confirms it and closes. */
    val doneMessage: String? = null,
)

/**
 * Backs [CompleteQuestActivity], the completion menu the home-screen widget opens
 * when a task row is tapped. Loads the quest by id and completes or skips it for the
 * given day via [QuestRepository.completeQuest] — all without opening the app.
 */
class QuickCompleteViewModel(
    private val repository: QuestRepository,
    private val questId: String,
    private val epochDay: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(QuickCompleteUiState())
    val state: StateFlow<QuickCompleteUiState> = _state.asStateFlow()

    private var quest: Quest? = null

    init {
        launchSafely(onError = { _state.update { it.copy(loading = false, notFound = true) } }) {
            val q = repository.activeQuestById(questId)
            quest = q
            _state.update {
                if (q == null) it.copy(loading = false, notFound = true)
                else it.copy(loading = false, title = q.title)
            }
        }
    }

    fun complete() = submit(CompletionResult.COMPLETED, "Nice — marked done ✓")

    fun skip() = submit(CompletionResult.SKIPPED, "Skipped for today.")

    private fun submit(result: CompletionResult, message: String) {
        val q = quest ?: return
        if (_state.value.submitting) return
        launchSafely(onError = { _state.update { it.copy(submitting = false, error = "Something went wrong — try again.") } }) {
            _state.update { it.copy(submitting = true, error = null) }
            repository.completeQuest(q, epochDay, result)
            _state.update { it.copy(submitting = false, doneMessage = message) }
        }
    }

    /** Clears the one-shot [QuickCompleteUiState.doneMessage] after the activity shows it. */
    fun consumeDone() = _state.update { it.copy(doneMessage = null) }
}
