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

    /** One-shot message describing the last quick-add result (AI vs fallback). */
    private val _quickResult = MutableStateFlow<String?>(null)
    val quickResult: StateFlow<String?> = _quickResult.asStateFlow()

    fun consumeQuickResult() { _quickResult.value = null }

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
     * otherwise. Reports the outcome via [quickResult] (the screen stays open so
     * the user can see what was added and add more).
     */
    fun quickAddFromText(text: String) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        viewModelScope.launch {
            _generating.value = true
            try {
                val suggestion = repository.suggestQuests(lines)
                suggestion.quests.forEach { repository.addQuest(it.copy(id = "user-${UUID.randomUUID()}")) }
                val n = suggestion.quests.size
                _quickResult.value = when {
                    n == 0 -> "Nothing to add — try rephrasing."
                    suggestion.fromAi -> "Added $n quest${if (n == 1) "" else "s"} ✨"
                    else -> "Added $n quest${if (n == 1) "" else "s"}."
                }
            } catch (e: Exception) {
                _quickResult.value = "AI didn't respond. Check your key in Settings."
            } finally {
                _generating.value = false
            }
        }
    }
}
