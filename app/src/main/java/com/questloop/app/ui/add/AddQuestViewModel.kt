package com.questloop.app.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.questloop.app.data.QuestRepository
import com.questloop.core.ai.AiQuestValidator
import com.questloop.core.ai.FallbackSuggester
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlinx.coroutines.launch
import java.util.UUID

class AddQuestViewModel(private val repository: QuestRepository) : ViewModel() {

    private val validator = AiQuestValidator()

    fun addQuest(
        title: String,
        category: QuestCategory,
        difficulty: Difficulty,
        priority: Priority,
        frequency: QuestFrequency,
        estimatedMinutes: Int,
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
        )
        viewModelScope.launch {
            repository.addQuest(quest)
            onDone()
        }
    }

    /**
     * Turns free-text todo lines into safe quests. In the MVP this uses the
     * deterministic [FallbackSuggester] passed through the same [AiQuestValidator]
     * guardrails an LLM response would face, so the path is identical when a
     * model is wired in later.
     */
    fun quickAddFromText(text: String, onDone: () -> Unit) {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val suggested = FallbackSuggester.suggest(lines, emptySet(), max = lines.size.coerceAtLeast(1))
        val validated = validator.validate(suggested)
        viewModelScope.launch {
            validated.accepted.forEach {
                repository.addQuest(it.copy(id = "user-${UUID.randomUUID()}"))
            }
            onDone()
        }
    }
}
