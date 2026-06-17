package com.questloop.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.SampleData
import com.questloop.app.ui.AppClock
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class AddUiState(
    /** True while the initial suggestion request is in flight. */
    val generating: Boolean = false,
    /** Editable AI/fallback suggestions awaiting the user's review. */
    val suggestions: List<Quest> = emptyList(),
    /** Id of the suggestion currently being refined by AI, if any. */
    val refiningId: String? = null,
    /** True while suggestions are being persisted (guards double-add). */
    val saving: Boolean = false,
    /** One-shot status/error message for the quick-add flow. */
    val message: String? = null,
)

class AddQuestViewModel(private val repository: QuestRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()

    fun addQuest(
        title: String,
        category: QuestCategory,
        difficulty: Difficulty,
        priority: Priority,
        frequency: QuestFrequency,
        estimatedMinutes: Int,
        completionStyle: com.questloop.core.model.CompletionStyle = com.questloop.core.model.CompletionStyle.BINARY,
        targetCount: Int? = null,
        unit: String? = null,
        onDone: () -> Unit,
    ) {
        val quest = Quest(
            id = "user-${UUID.randomUUID()}",
            title = title.trim(),
            category = category,
            frequency = frequency,
            difficulty = difficulty,
            priority = priority,
            estimatedMinutes = estimatedMinutes,
            isReductionQuest = category == QuestCategory.BAD_HABIT_REDUCTION,
            completionStyle = completionStyle,
            targetCount = targetCount,
            unit = unit,
        )
        viewModelScope.launch {
            repository.addQuest(quest)
            repository.completeOnboardingQuest(SampleData.ONBOARDING_CREATE, AppClock.todayEpochDay())
            onDone()
        }
    }

    /**
     * Turns free-text into suggestions for review (NOT added yet). The user's text
     * is left untouched so they can tweak and re-generate. AI failures are shown;
     * when AI is off, deterministic suggestions are offered for review instead.
     */
    fun generate(text: String) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(generating = true, message = null) }
            val suggestion = repository.suggestQuests(lines)
            val n = suggestion.quests.size
            val plural = if (n == 1) "" else "s"
            _state.update {
                it.copy(
                    generating = false,
                    suggestions = suggestion.quests,
                    message = when {
                        suggestion.error != null ->
                            "${suggestion.error} Showing your text below — edit, refine, or discard."
                        n == 0 -> "Nothing to suggest — try rephrasing."
                        suggestion.fromAi -> "Review $n suggestion$plural below."
                        else -> "AI is off — showing basic suggestions. Turn it on in Settings for smarter ones."
                    },
                )
            }
        }
    }

    /**
     * Breaks one goal into a short ladder of reviewable quests (NOT added yet),
     * reusing the same review/accept flow as quick-add.
     */
    fun decomposeGoal(goal: String) {
        if (goal.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(generating = true, message = null) }
            val suggestion = repository.decomposeGoal(goal)
            val n = suggestion.quests.size
            val plural = if (n == 1) "" else "s"
            _state.update {
                it.copy(
                    generating = false,
                    suggestions = suggestion.quests,
                    message = when {
                        suggestion.error != null ->
                            "${suggestion.error} Showing a starting point below — edit or discard."
                        n == 0 -> "Couldn't break that down — try rephrasing the goal."
                        suggestion.fromAi -> "Broke it into $n step$plural — review below."
                        else -> "Here's a starting point. Turn on AI in Settings for a full breakdown."
                    },
                )
            }
        }
    }

    /** Replaces a suggestion after a manual edit (same id). */
    fun updateSuggestion(quest: Quest) {
        _state.update { st -> st.copy(suggestions = st.suggestions.map { if (it.id == quest.id) quest else it }) }
    }

    fun removeSuggestion(id: String) {
        _state.update { st -> st.copy(suggestions = st.suggestions.filterNot { it.id == id }) }
    }

    /** Asks the AI to revise one suggestion per a free-text instruction. */
    fun refineSuggestion(id: String, instruction: String) {
        val target = _state.value.suggestions.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(refiningId = id, message = null) }
            val result = repository.refineQuest(target, instruction)
            val revised = result.quest // local val so it smart-casts inside the lambda
            _state.update { st ->
                st.copy(
                    refiningId = null,
                    suggestions = if (revised != null) {
                        st.suggestions.map { if (it.id == id) revised else it }
                    } else {
                        st.suggestions
                    },
                    message = result.error ?: if (revised != null) "Updated that quest. ✨" else st.message,
                )
            }
        }
    }

    /** Persists one reviewed suggestion. Guards against double-tap duplicates. */
    fun acceptSuggestion(id: String) {
        if (_state.value.saving) return
        val quest = _state.value.suggestions.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.addQuest(quest.copy(id = "user-${UUID.randomUUID()}", title = quest.title.trim()))
            repository.completeOnboardingQuest(SampleData.ONBOARDING_CREATE, AppClock.todayEpochDay())
            _state.update {
                it.copy(
                    saving = false,
                    suggestions = it.suggestions.filterNot { s -> s.id == id },
                    message = "Added \"${quest.title.trim()}\".",
                )
            }
        }
    }

    /** Persists all reviewed suggestions with a non-blank title. Guards double-tap. */
    fun acceptAll() {
        if (_state.value.saving) return
        val all = _state.value.suggestions.filter { it.title.isBlank().not() }
        if (all.isEmpty()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            all.forEach { repository.addQuest(it.copy(id = "user-${UUID.randomUUID()}", title = it.title.trim())) }
            repository.completeOnboardingQuest(SampleData.ONBOARDING_CREATE, AppClock.todayEpochDay())
            val plural = if (all.size == 1) "" else "s"
            _state.update { it.copy(saving = false, suggestions = emptyList(), message = "Added ${all.size} quest$plural.") }
        }
    }

}
