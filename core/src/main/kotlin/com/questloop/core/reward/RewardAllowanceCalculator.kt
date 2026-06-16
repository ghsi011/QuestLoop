package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Priority
import kotlin.math.roundToInt

/**
 * Suggests a *self-imposed* monthly real-world reward allowance (SPEC section 6).
 *
 * The app never holds, moves, or invests money. It only suggests, from the
 * user's own affordable budget cap, how much of that budget they've "earned"
 * this month based on completion, difficulty, consistency and priority — and it
 * always returns the standard disclaimers.
 */
object RewardAllowanceCalculator {

    /** Required, non-removable disclaimers shown wherever an allowance appears. */
    val DISCLAIMERS: List<String> = listOf(
        "This is not financial advice.",
        "QuestLoop does not hold, move, or invest your money.",
        "You control all funds externally and set your own budget.",
        "Only set aside what you can comfortably afford.",
    )

    data class AllowanceInput(
        /** The user's own affordable monthly budget cap, in their currency. */
        val monthlyBudgetCap: Double,
        val records: List<CompletionRecord>,
        /** Days in the month the user was active (for consistency weighting). */
        val activeDays: Int,
        val daysInMonth: Int = 30,
    )

    data class AllowanceResult(
        val suggestedAllowance: Double,
        val budgetCap: Double,
        val earnedFraction: Double,
        val completionRate: Double,
        val consistencyFactor: Double,
        val criticalTasksMissed: Int,
        val explanation: String,
        val disclaimers: List<String> = DISCLAIMERS,
    )

    fun calculate(input: AllowanceInput): AllowanceResult {
        val cap = input.monthlyBudgetCap.coerceAtLeast(0.0)
        if (cap <= 0.0 || input.records.isEmpty()) {
            return AllowanceResult(
                suggestedAllowance = 0.0,
                budgetCap = cap,
                earnedFraction = 0.0,
                completionRate = 0.0,
                consistencyFactor = 0.0,
                criticalTasksMissed = 0,
                explanation = if (cap <= 0.0)
                    "Set an affordable monthly budget cap to see a suggested allowance."
                else "No quest activity recorded yet this month.",
            )
        }

        val attempted = input.records.count { it.result != CompletionResult.RESCHEDULED }
        val completedWeighted = input.records.sumOf {
            when (it.result) {
                CompletionResult.COMPLETED -> it.difficulty.weight
                CompletionResult.PARTIAL -> it.difficulty.weight * it.fraction
                else -> 0.0
            }
        }
        val attemptedWeighted = input.records
            .filter { it.result != CompletionResult.RESCHEDULED }
            .sumOf { it.difficulty.weight }
            .coerceAtLeast(1.0)

        // Difficulty-weighted completion rate (harder tasks count more, SPEC 8).
        val completionRate = (completedWeighted / attemptedWeighted).coerceIn(0.0, 1.0)

        // Guard the denominator: a zero/negative month span would yield NaN, which
        // slips through coerceIn (all NaN comparisons are false) into the figure.
        val daysInMonth = input.daysInMonth.coerceAtLeast(1)
        val consistencyFactor = (input.activeDays.coerceAtLeast(0).toDouble() / daysInMonth.toDouble())
            .coerceIn(0.0, 1.0)

        val criticalMissed = input.records.count {
            it.priority == Priority.CRITICAL &&
                (it.result == CompletionResult.FAILED || it.result == CompletionResult.SKIPPED)
        }
        // Each missed critical task gently reduces the earned fraction.
        val criticalPenalty = (criticalMissed * 0.05).coerceAtMost(0.3)

        // Blend: completion is the main driver, consistency a meaningful modifier.
        val earnedFraction = ((completionRate * 0.7 + consistencyFactor * 0.3) - criticalPenalty)
            .coerceIn(0.0, 1.0)

        val suggested = (cap * earnedFraction).roundToCents()

        val explanation = buildString {
            append("Suggested allowance: ${suggested.money()} of your ${cap.money()} budget. ")
            append("Based on ${(completionRate * 100).roundToInt()}% difficulty-weighted completion ")
            append("and ${(consistencyFactor * 100).roundToInt()}% day-to-day consistency")
            if (criticalMissed > 0) append("; reduced for $criticalMissed missed critical task(s)")
            append(". Spend only what fits your budget.")
        }

        return AllowanceResult(
            suggestedAllowance = suggested,
            budgetCap = cap,
            earnedFraction = earnedFraction,
            completionRate = completionRate,
            consistencyFactor = consistencyFactor,
            criticalTasksMissed = criticalMissed,
            explanation = explanation,
        )
    }

    private fun Double.roundToCents(): Double = (this * 100).roundToInt() / 100.0
    private fun Double.money(): String = "%.2f".format(this)
}
