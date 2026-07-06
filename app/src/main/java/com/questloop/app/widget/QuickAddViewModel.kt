package com.questloop.app.widget

import androidx.lifecycle.ViewModel
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.QuickAddResult
import com.questloop.app.data.SampleData
import com.questloop.app.ui.AppClock
import com.questloop.app.util.launchSafely
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class QuickAddUiState(
    val input: String = "",
    /** True while the generate-and-save round trip is in flight. */
    val submitting: Boolean = false,
    /** Inline, user-facing reason the last submit didn't add anything; null when fine. */
    val error: String? = null,
    /** Set once a quest is saved; the host activity confirms it and closes. */
    val addedTitle: String? = null,
)

/**
 * Backs [AddQuestActivity], the lightweight dialog the home-screen widget opens to
 * add a quest. Home-screen widgets can't host an editable field, so the widget's
 * "add" box launches this activity; the typed text goes straight through the AI
 * generation flow and is saved as a single one-off quest with no review step
 * ([QuestRepository.addOneOffQuestFromText]).
 */
class QuickAddViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(QuickAddUiState())
    val state: StateFlow<QuickAddUiState> = _state.asStateFlow()

    /** Editing the text clears any prior error so the field stops looking failed. */
    fun onInputChange(text: String) = _state.update { it.copy(input = text, error = null) }

    /** Runs the typed text through generate → save (one one-off quest, no review). */
    fun submit() {
        val text = _state.value.input.trim()
        if (text.isBlank() || _state.value.submitting) return
        launchSafely(onError = { _state.update { it.copy(submitting = false, error = "Something went wrong — try again.") } }) {
            _state.update { it.copy(submitting = true, error = null) }
            when (val result = repository.addOneOffQuestFromText(text)) {
                is QuickAddResult.Added -> {
                    // Adding a real quest satisfies the "create your first quest"
                    // onboarding step, same as the in-app add flow.
                    repository.completeOnboardingQuest(SampleData.ONBOARDING_CREATE, AppClock.todayEpochDay())
                    _state.update { it.copy(submitting = false, addedTitle = result.quest.title) }
                }
                is QuickAddResult.Failed ->
                    _state.update { it.copy(submitting = false, error = result.message) }
                QuickAddResult.Empty ->
                    _state.update { it.copy(submitting = false, error = "Add a few words to turn into a quest.") }
            }
        }
    }
}
