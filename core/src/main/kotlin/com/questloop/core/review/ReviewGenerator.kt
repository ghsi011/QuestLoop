package com.questloop.core.review

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.QuestCategory
import kotlin.math.roundToInt

/**
 * Produces weekly / monthly review summaries (SPEC sections 3 & 5).
 *
 * Pure aggregation over completion records: totals, per-category breakdowns,
 * consistency, bad-habit progress, and a few plain-language, non-judgemental
 * highlights and suggestions.
 */
object ReviewGenerator {

    data class CategoryStat(
        val category: QuestCategory,
        val completed: Int,
        val attempted: Int,
        val xp: Long,
    ) {
        val completionRate: Double get() = if (attempted == 0) 0.0 else completed.toDouble() / attempted
    }

    data class Review(
        val periodLabel: String,
        val totalCompleted: Int,
        val totalAttempted: Int,
        val xpEarned: Long,
        val activeDays: Int,
        val completionRate: Double,
        val byCategory: List<CategoryStat>,
        val mostNeglectedCategory: QuestCategory?,
        val strongestCategory: QuestCategory?,
        val highlights: List<String>,
        val suggestions: List<String>,
    )

    fun generate(
        periodLabel: String,
        records: List<CompletionRecord>,
        xpByRecord: (CompletionRecord) -> Long = { 0L },
    ): Review {
        val attempted = records.filter { it.result != CompletionResult.RESCHEDULED }
        val completed = records.filter {
            it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL
        }
        val xpEarned = records.sumOf { xpByRecord(it) }
        val activeDays = records
            .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL }
            .map { it.epochDay }
            .toSet().size

        val byCategory = QuestCategory.entries.map { cat ->
            val catRecords = records.filter { it.category == cat }
            CategoryStat(
                category = cat,
                completed = catRecords.count {
                    it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL
                },
                attempted = catRecords.count { it.result != CompletionResult.RESCHEDULED },
                xp = catRecords.sumOf { xpByRecord(it) },
            )
        }.filter { it.attempted > 0 }

        val strongest = byCategory.maxByOrNull { it.completionRate }?.category
        val neglected = byCategory
            .filter { it.attempted >= 2 }
            .minByOrNull { it.completionRate }
            ?.takeIf { it.completionRate < 0.5 }
            ?.category

        val completionRate = if (attempted.isEmpty()) 0.0
        else completed.size.toDouble() / attempted.size

        val highlights = buildHighlights(completed.size, xpEarned, activeDays, strongest, records)
        val suggestions = buildSuggestions(completionRate, neglected, records)

        return Review(
            periodLabel = periodLabel,
            totalCompleted = completed.size,
            totalAttempted = attempted.size,
            xpEarned = xpEarned,
            activeDays = activeDays,
            completionRate = completionRate,
            byCategory = byCategory,
            mostNeglectedCategory = neglected,
            strongestCategory = strongest,
            highlights = highlights,
            suggestions = suggestions,
        )
    }

    private fun buildHighlights(
        completed: Int,
        xp: Long,
        activeDays: Int,
        strongest: QuestCategory?,
        records: List<CompletionRecord>,
    ): List<String> {
        val out = mutableListOf<String>()
        out += "Completed $completed quests across $activeDays active days (+$xp XP)."
        strongest?.let { out += "Strongest area: ${it.pretty()}." }
        val honestyLogs = records.count {
            it.category == QuestCategory.BAD_HABIT_REDUCTION
        }
        if (honestyLogs > 0) {
            out += "You tracked bad-habit progress $honestyLogs time(s) — honesty is a real win."
        }
        return out
    }

    private fun buildSuggestions(
        completionRate: Double,
        neglected: QuestCategory?,
        records: List<CompletionRecord>,
    ): List<String> {
        val out = mutableListOf<String>()
        neglected?.let {
            out += "${it.pretty()} slipped a bit — want a smaller, easier quest there next period?"
        }
        if (completionRate < 0.4 && records.size >= 5) {
            out += "Completion was on the low side. Lightening the daily load often rebuilds momentum."
        }
        if (completionRate > 0.9 && records.size >= 10) {
            out += "Great consistency. You could take on one slightly harder quest — only if you want to."
        }
        if (out.isEmpty()) out += "Nicely balanced period. Keep doing what works for you."
        return out
    }

    private fun QuestCategory.pretty(): String =
        name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}
