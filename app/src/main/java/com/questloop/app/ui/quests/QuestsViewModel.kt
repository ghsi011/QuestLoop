package com.questloop.app.ui.quests

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.util.launchSafely
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.AppClock
import com.questloop.app.ui.today.PendingUndo
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

data class QuestsUiState(
    val loading: Boolean = true,
    /** All not-yet-done quests with their status for today, grouped for display. */
    val groups: List<QuestGroup> = emptyList(),
    val total: Int = 0,
    val toast: String? = null,
    /** Bumped on every toast so identical consecutive messages still re-fire. */
    val toastId: Long = 0,
    /** Data to reverse the last completion via the snackbar "Undo". */
    val pendingUndo: PendingUndo? = null,
    /** True while a completion is being recorded; guards double-taps. */
    val completing: Boolean = false,
)

/** A titled section of the backlog (e.g. "Today's plan", "Scheduled for later"). */
data class QuestGroup(val title: String, val items: List<QuestRepository.QuestStatus>)

/**
 * Backs the Quests screen: the full, transparent backlog of everything the user
 * created or accepted from AI. Quests are completed with their real controls
 * (counting/timed/subjective/reduction) and disappear once done for the day.
 */
class QuestsViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(QuestsUiState())
    val state: StateFlow<QuestsUiState> = _state.asStateFlow()

    init {
        // Recompute whenever the active quest set changes (e.g. AI quick-add adds
        // quests on another screen), so the backlog is always current.
        launchSafely(onError = ::refreshFailed) {
            repository.quests.collectLatest {
                try {
                    recompute()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // One failed recompute must not kill the subscription: report
                    // it and keep collecting so the next change still refreshes.
                    refreshFailed(t)
                }
            }
        }
    }

    private suspend fun recompute() {
        val today = AppClock.todayEpochDay()
        val overview = repository.questOverview(today, AppClock.currentDayPart())
        _state.update { it.copy(loading = false, groups = group(overview), total = overview.size) }
    }

    /** A failed refresh releases the spinner and says so, instead of freezing. */
    private fun refreshFailed(t: Throwable) {
        runCatching { Log.e("QuestLoop", "Quest backlog refresh failed", t) }
        _state.update {
            it.copy(
                loading = false,
                toast = "Couldn't refresh your quests — please try again.",
                toastId = it.toastId + 1,
                pendingUndo = null,
            )
        }
    }

    fun complete(quest: Quest) = completeWithUndo("Nice — done. ✨") {
        repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.COMPLETED)
    }

    fun skip(quest: Quest) =
        completeWithUndo(if (quest.isReductionQuest) "Logged honestly." else "Skipped for today.") {
            repository.completeQuest(quest, AppClock.todayEpochDay(), CompletionResult.SKIPPED)
        }

    fun completeMeasured(quest: Quest, value: Int) = completeWithUndo("Progress logged. ✨") {
        repository.completeMeasured(quest, AppClock.todayEpochDay(), value)
    }

    /** Runs a completion (guarded against double-tap) and offers Undo. */
    private fun completeWithUndo(toast: String, block: suspend () -> QuestRepository.CompleteOutcome) {
        if (_state.value.completing) return
        _state.update { it.copy(completing = true) }
        launchSafely {
            try {
                val outcome = block()
                _state.update {
                    it.copy(
                        toast = toast,
                        toastId = it.toastId + 1,
                        pendingUndo = PendingUndo(outcome.instanceId, outcome.previousRecord),
                    )
                }
                recompute()
            } finally {
                _state.update { it.copy(completing = false) }
            }
        }
    }

    fun undoLast() {
        val undo = _state.value.pendingUndo ?: return
        launchSafely {
            repository.undoCompletion(undo.instanceId, undo.previous)
            _state.update { it.copy(toast = null, pendingUndo = null) }
            recompute()
        }
    }

    fun delete(quest: Quest) {
        launchSafely {
            repository.archiveQuest(quest.id)
            _state.update { it.copy(toast = "Deleted \"${quest.title}\".", toastId = it.toastId + 1, pendingUndo = null) }
            recompute()
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null, pendingUndo = null) }

    /**
     * Groups the not-yet-done backlog: what's on for today, what else is due, and
     * what's scheduled for later. Done quests drop out entirely (they reappear
     * tomorrow if they recur).
     */
    private fun group(items: List<QuestRepository.QuestStatus>): List<QuestGroup> {
        val open = items.filterNot { it.done }
        val inPlan = open.filter { it.inTodaysPlan }
        val alsoDue = open.filter { !it.inTodaysPlan && it.dueToday }
        // A not-due ONE_OFF has already been done, so it's finished — don't list it
        // as "scheduled for later" (that's only for recurring quests).
        val later = open.filter {
            !it.inTodaysPlan && !it.dueToday && it.quest.frequency != QuestFrequency.ONE_OFF
        }
        return buildList {
            if (inPlan.isNotEmpty()) add(QuestGroup("Today's plan", inPlan))
            if (alsoDue.isNotEmpty()) add(QuestGroup("Also due today", alsoDue))
            if (later.isNotEmpty()) add(QuestGroup("Scheduled for later", later))
        }
    }
}
