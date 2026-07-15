package com.questloop.app.widget

import androidx.lifecycle.ViewModel
import com.questloop.app.data.QuestRepository
import com.questloop.app.util.launchSafely
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Quest
import com.questloop.core.reward.CompletionSound
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class QuickCompleteUiState(
    /** True until the quest has been looked up. */
    val loading: Boolean = true,
    /** The quest to act on couldn't be found (already done elsewhere, or archived). */
    val notFound: Boolean = false,
    /** The loaded quest — drives the style-aware controls (stepper / rating / binary). */
    val quest: Quest? = null,
    val title: String = "",
    /**
     * Accumulated progress this interval for a measured quest (count for
     * QUANTITATIVE, minutes for DURATION), so the stepper resumes from where the
     * user left off instead of restarting at zero.
     */
    val progress: Int = 0,
    /** True while a complete/skip is in flight (guards double-tap). */
    val submitting: Boolean = false,
    /** Inline error that keeps the menu open so the user can retry. */
    val error: String? = null,
    /** Set once the action lands; the host activity confirms it and closes. */
    val doneMessage: String? = null,
    /** Celebration chime the landed completion earned; played with [doneMessage]. */
    val sound: CompletionSound? = null,
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
            if (q == null) {
                _state.update { it.copy(loading = false, notFound = true) }
            } else {
                // Seed the stepper from the interval's accumulated progress so a measured
                // quest resumes mid-log rather than restarting at zero. A progress-read
                // failure degrades to a zero seed — the quest was found, so it must NOT
                // fall through to onError's notFound (which would close the menu as if
                // the quest were gone and drop a completion the user could still make).
                val progress = runCatching { repository.todayProgress(epochDay)[q.id] }.getOrNull() ?: 0
                _state.update { it.copy(loading = false, quest = q, title = q.title, progress = progress) }
            }
        }
    }

    /**
     * Full-credit "done". Wired to the BINARY control's Complete button (measured
     * styles log via [logMeasured] instead); still routes through [completeMeasured]
     * with a full-target value so it credits correctly for any style if reused.
     */
    fun complete() {
        val q = quest ?: return
        // Route measured quests through completeMeasured so a counting/timed/subjective
        // quest is credited to its target with the right scaling + verification method,
        // rather than force-writing a binary fraction=1.0/MANUAL record over its progress.
        val value = when (q.completionStyle) {
            CompletionStyle.QUANTITATIVE -> q.targetCount ?: 1
            CompletionStyle.DURATION -> q.estimatedMinutes
            CompletionStyle.SUBJECTIVE -> 5
            CompletionStyle.BINARY -> 1
        }
        perform("Nice — marked done ✓") { repository.completeMeasured(q, epochDay, value) }
    }

    /**
     * Logs a measured value for a non-binary quest: a running count (QUANTITATIVE),
     * minutes (DURATION), or a 1..5 rating (SUBJECTIVE). Progress is monotonic and
     * credited proportionally — a partial log is never a skip.
     */
    fun logMeasured(value: Int) {
        val q = quest ?: return
        perform("Progress logged ✓") { repository.completeMeasured(q, epochDay, value) }
    }

    fun skip() {
        val q = quest ?: return
        perform("Skipped for today.") { repository.completeQuest(q, epochDay, CompletionResult.SKIPPED) }
    }

    private fun perform(message: String, action: suspend () -> QuestRepository.CompleteOutcome) {
        if (_state.value.submitting) return
        launchSafely(onError = { _state.update { it.copy(submitting = false, error = "Something went wrong — try again.") } }) {
            _state.update { it.copy(submitting = true, error = null) }
            // Non-cancellable: the dialog stays dismissable while submitting, and
            // finishing the activity cancels viewModelScope — a started complete/skip
            // must still land rather than be silently dropped mid-write.
            val outcome = withContext(NonCancellable) { action() }
            _state.update { it.copy(submitting = false, doneMessage = message, sound = outcome.sound) }
        }
    }

    /** Clears the one-shot [QuickCompleteUiState.doneMessage] after the activity shows it. */
    fun consumeDone() = _state.update { it.copy(doneMessage = null, sound = null) }
}
