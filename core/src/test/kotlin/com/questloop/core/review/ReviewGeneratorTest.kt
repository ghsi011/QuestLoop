package com.questloop.core.review

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewGeneratorTest {

    private fun rec(
        day: Long,
        category: QuestCategory,
        result: CompletionResult = CompletionResult.COMPLETED,
        id: String = "q",
    ) = CompletionRecord(
        instanceId = "$id@$day",
        questId = id,
        category = category,
        difficulty = Difficulty.MEDIUM,
        priority = Priority.NORMAL,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        isMeta = category.isMeta,
    )

    @Test
    fun `aggregates totals and active days`() {
        val records = listOf(
            rec(100, QuestCategory.HEALTH, id = "a"),
            rec(100, QuestCategory.WORK_STUDY, id = "b"),
            rec(101, QuestCategory.CHORES, id = "c"),
            rec(102, QuestCategory.HEALTH, result = CompletionResult.SKIPPED, id = "d"),
        )
        val review = ReviewGenerator.generate("This week", records) { 10 }
        assertEquals(3, review.totalCompleted)
        assertEquals(4, review.totalAttempted)
        assertEquals(2, review.activeDays)
        assertEquals(40L, review.xpEarned)
    }

    @Test
    fun `identifies neglected category`() {
        val records = listOf(
            rec(100, QuestCategory.HEALTH, id = "a"),
            rec(101, QuestCategory.HEALTH, id = "b"),
            rec(100, QuestCategory.SOCIAL, result = CompletionResult.SKIPPED, id = "c"),
            rec(101, QuestCategory.SOCIAL, result = CompletionResult.SKIPPED, id = "d"),
        )
        val review = ReviewGenerator.generate("This week", records)
        assertEquals(QuestCategory.SOCIAL, review.mostNeglectedCategory)
        assertEquals(QuestCategory.HEALTH, review.strongestCategory)
    }

    @Test
    fun `suggestions are non-empty and supportive`() {
        val records = (1..10).map { rec(it.toLong(), QuestCategory.WORK_STUDY, id = "q$it") }
        val review = ReviewGenerator.generate("This month", records)
        assertTrue(review.suggestions.isNotEmpty())
        assertTrue(review.highlights.isNotEmpty())
    }

    @Test
    fun `zero-progress partial does not inflate completed or active days`() {
        val records = listOf(
            rec(100, QuestCategory.HEALTH, id = "a"), // genuine completion
            // PARTIAL with fraction 0.0 (helper) -> countsAsActivity is false.
            rec(101, QuestCategory.HEALTH, result = CompletionResult.PARTIAL, id = "b"),
        )
        val review = ReviewGenerator.generate("This week", records) { 10 }
        assertEquals(1, review.totalCompleted)
        assertEquals(1, review.activeDays)
        assertEquals(2, review.totalAttempted) // both still count as attempts
    }

    @Test
    fun `empty period does not crash`() {
        val review = ReviewGenerator.generate("Empty", emptyList())
        assertEquals(0, review.totalCompleted)
        assertEquals(0.0, review.completionRate)
        assertTrue(review.suggestions.isNotEmpty())
    }
}
