package com.questloop.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class AddQuestViewModel(private val repository: QuestRepository) : ViewModel() {

    /** True while an AI suggestion request is in flight. */
    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

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
            onDone()
        }
    }

    /**
     * Turns free-text todo lines into quests. Routes through the repository's AI
     * suggester when configured (guardrailed), and a deterministic safe fallback
     * otherwise — so the button always produces something sensible.
     */
    fun quickAddFromText(text: String, onDone: () -> Unit) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _generating.value = true
            try {
                repository.suggestQuests(lines).forEach {
                    repository.addQuest(it.copy(id = "user-${UUID.randomUUID()}"))
                }
            } finally {
                _generating.value = false
            }
            onDone()
        }
    }
}
