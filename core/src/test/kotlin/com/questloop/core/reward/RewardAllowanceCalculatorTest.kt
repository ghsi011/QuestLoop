package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardAllowanceCalculatorTest {

    private fun rec(
        result: CompletionResult,
        difficulty: Difficulty = Difficulty.MEDIUM,
        priority: Priority = Priority.NORMAL,
        day: Long = 1,
    ) = CompletionRecord(
        instanceId = "x",
        questId = "x",
        category = QuestCategory.WORK_STUDY,
        difficulty = difficulty,
        priority = priority,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
    )

    @Test
    fun `disclaimers are always present`() {
        val result = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = 100.0,
                records = listOf(rec(CompletionResult.COMPLETED)),
                activeDays = 1,
            ),
        )
        assertTrue(result.disclaimers.any { it.contains("not financial advice", ignoreCase = true) })
        assertTrue(result.disclaimers.any { it.contains("does not hold", ignoreCase = true) })
    }

    @Test
    fun `zero budget yields zero allowance with guidance`() {
        val result = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = 0.0,
                records = listOf(rec(CompletionResult.COMPLETED)),
                activeDays = 1,
            ),
        )
        assertEquals(0.0, result.suggestedAllowance)
        assertTrue(result.explanation.contains("budget", ignoreCase = true))
    }

    @Test
    fun `allowance never exceeds budget cap`() {
        val records = (1..30).map { rec(CompletionResult.COMPLETED, day = it.toLong()) }
        val result = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = 200.0,
                records = records,
                activeDays = 30,
                daysInMonth = 30,
            ),
        )
        assertTrue(result.suggestedAllowance <= 200.0)
        assertTrue(result.suggestedAllowance > 150.0, "high completion should earn most of cap")
    }

    @Test
    fun `low completion earns proportionally less`() {
        val records = (1..10).map {
            rec(if (it <= 2) CompletionResult.COMPLETED else CompletionResult.SKIPPED, day = it.toLong())
        }
        val result = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = 100.0,
                records = records,
                activeDays = 2,
                daysInMonth = 30,
            ),
        )
        assertTrue(result.suggestedAllowance < 40.0, "got ${result.suggestedAllowance}")
    }

    @Test
    fun `missed critical tasks reduce the allowance`() {
        val baseRecords = (1..10).map { rec(CompletionResult.COMPLETED, day = it.toLong()) }
        val withMissedCritical = baseRecords + listOf(
            rec(CompletionResult.FAILED, priority = Priority.CRITICAL, day = 11),
            rec(CompletionResult.FAILED, priority = Priority.CRITICAL, day = 12),
        )
        val a = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(100.0, baseRecords, activeDays = 10),
        )
        val b = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(100.0, withMissedCritical, activeDays = 10),
        )
        assertTrue(b.suggestedAllowance < a.suggestedAllowance)
        assertEquals(2, b.criticalTasksMissed)
    }

    @Test
    fun `harder completed tasks count more than trivial ones`() {
        val hard = (1..5).map { rec(CompletionResult.COMPLETED, Difficulty.EPIC, day = it.toLong()) } +
            (6..10).map { rec(CompletionResult.SKIPPED, Difficulty.TRIVIAL, day = it.toLong()) }
        val trivial = (1..5).map { rec(CompletionResult.COMPLETED, Difficulty.TRIVIAL, day = it.toLong()) } +
            (6..10).map { rec(CompletionResult.SKIPPED, Difficulty.EPIC, day = it.toLong()) }
        val hardResult = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(100.0, hard, activeDays = 5),
        )
        val trivialResult = RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(100.0, trivial, activeDays = 5),
        )
        assertTrue(hardResult.suggestedAllowance > trivialResult.suggestedAllowance)
    }
}
