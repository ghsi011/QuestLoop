package com.questloop.core.ai

import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiQuestValidatorTest {

    private val validator = AiQuestValidator()

    private fun q(
        id: String,
        title: String,
        category: QuestCategory = QuestCategory.LIFE_ADMIN,
        minutes: Int = 20,
        rationale: String? = null,
    ) = Quest(
        id = id,
        title = title,
        category = category,
        frequency = QuestFrequency.ONE_OFF,
        difficulty = Difficulty.EASY,
        estimatedMinutes = minutes,
        rationale = rationale,
    )

    @Test
    fun `accepts clean quests`() {
        val result = validator.validate(listOf(q("1", "Pay electricity bill")))
        assertEquals(1, result.accepted.size)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `rejects shaming language`() {
        val result = validator.validate(listOf(q("1", "Stop being lazy and clean up")))
        assertTrue(result.accepted.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `rejects financial advice`() {
        val result = validator.validate(listOf(q("1", "Invest in crypto for guaranteed return")))
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun `rejects medical advice in rationale`() {
        val result = validator.validate(
            listOf(q("1", "Feel better", rationale = "This will diagnose your condition")),
        )
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun `clamps unrealistic time estimates`() {
        val result = validator.validate(listOf(q("1", "Read a book", minutes = 99999)))
        assertEquals(240, result.accepted.single().estimatedMinutes)
    }

    @Test
    fun `drops duplicates of existing quests`() {
        val existing = listOf(q("e", "Walk the dog"))
        val result = validator.validate(listOf(q("1", "  walk the   dog ")), existing)
        assertTrue(result.accepted.isEmpty())
        assertEquals(1, result.rejected.size)
    }

    @Test
    fun `drops blank titles`() {
        val result = validator.validate(listOf(q("1", "   ")))
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun `tags reduction quests correctly`() {
        val result = validator.validate(
            listOf(q("1", "Track cigarettes today", category = QuestCategory.BAD_HABIT_REDUCTION)),
        )
        assertTrue(result.accepted.single().isReductionQuest)
    }

    @Test
    fun `fallback always returns something safe`() {
        val fromTodos = FallbackSuggester.suggest(listOf("Email landlord"), emptySet())
        assertEquals(1, fromTodos.size)
        val fromEmpty = FallbackSuggester.suggest(emptyList(), setOf(QuestCategory.HEALTH))
        assertEquals(1, fromEmpty.size)
        assertTrue(fromEmpty.single().category == QuestCategory.HEALTH)
    }
}
