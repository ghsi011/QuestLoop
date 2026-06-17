package com.questloop.core.ai

import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.QuestCategory
import com.questloop.core.review.ReviewGenerator
import kotlin.math.roundToInt

/**
 * Two short AI narrations, both built the same safe way as [AiQuestService]:
 * the model only rephrases facts a deterministic engine already computed (no raw
 * quest titles are ever sent — privacy), and every output is run through
 * [NarrationSanitizer]. Anything that reads as AI slop is rejected and replaced
 * with a terse, hand-written line built from the same facts, so the feature
 * always returns something clean even when AI is off, unavailable, or sloppy.
 *
 * Pure and unit-testable with a fake [LlmClient].
 */
class AiNarrator(private val client: LlmClient) {

    /** [fromAi] is false when the deterministic fallback was used. [note] is for diagnostics only. */
    data class Narration(val text: String, val fromAi: Boolean, val note: String? = null)

    /** Facts the plan rationale needs, derived from a [QuestGenerator.DailyPlan]. No titles. */
    data class PlanFacts(
        val energy: Int?,
        val lowEnergyDay: Boolean,
        val timeBudgetEnforced: Boolean,
        val availableMinutes: Int?,
        val tasksToday: Int,
        val totalMinutes: Int,
        val deferredCount: Int,
        val categoryMix: String,
        val due: String,
        // MUST stay engine-generated only (e.g. "Low-energy day detected"). Never route
        // user-authored text into plan.notes — it is forwarded to the model verbatim.
        val firstNote: String?,
    ) {
        companion object {
            fun from(
                plan: QuestGenerator.DailyPlan,
                checkIn: EnergyCheckIn?,
                availableMinutes: Int,
                epochDay: Long,
            ): PlanFacts {
                val energy = checkIn?.energy
                val cats = plan.quests.map { it.quest.category }.distinct()
                val mix = when {
                    cats.isEmpty() -> "none"
                    cats.size == 1 -> cats[0].pretty()
                    else -> "mixed: " + cats.take(3).joinToString(", ") { it.pretty() }
                }
                val deadlines = plan.quests.mapNotNull { it.quest.deadlineEpochDay }
                val due = when {
                    deadlines.any { it < epochDay } -> "overdue"
                    deadlines.any { it == epochDay } -> "due today"
                    else -> "none"
                }
                return PlanFacts(
                    energy = energy,
                    lowEnergyDay = energy != null && energy <= 2,
                    timeBudgetEnforced = checkIn != null,
                    availableMinutes = availableMinutes,
                    tasksToday = plan.quests.size,
                    totalMinutes = plan.totalEstimatedMinutes,
                    deferredCount = plan.deferred.size,
                    categoryMix = mix,
                    due = due,
                    firstNote = plan.notes.firstOrNull(),
                )
            }
        }
    }

    /**
     * @param sanitize when false, the anti-slop gate is bypassed and the raw model
     *   text is shown (still falling back on a transport failure or empty output).
     */
    suspend fun narrateReview(review: ReviewGenerator.Review, sanitize: Boolean = true): Narration {
        val attempt = runCatching {
            client.complete(PromptLibrary.REVIEW_NARRATION_SYSTEM, reviewPayload(review))
        }
        if (attempt.isFailure) return Narration(reviewFallback(review), false, failNote(attempt))
        if (!sanitize) {
            val clean = NarrationSanitizer.cosmetic(attempt.getOrNull())
            return if (clean.isNotBlank()) Narration(clean, true, "unsanitized")
            else Narration(reviewFallback(review), false, "empty")
        }
        val gate = NarrationSanitizer.gate(attempt.getOrNull(), NarrationSanitizer.Mode.REVIEW)
        return gate.text?.let { Narration(it, true) }
            ?: Narration(reviewFallback(review), false, "style:${gate.reason}")
    }

    suspend fun rationale(facts: PlanFacts, sanitize: Boolean = true): Narration {
        val attempt = runCatching {
            client.complete(PromptLibrary.PLAN_RATIONALE_SYSTEM, planPayload(facts))
        }
        if (attempt.isFailure) return Narration(planFallback(facts), false, failNote(attempt))
        if (!sanitize) {
            val clean = NarrationSanitizer.cosmetic(attempt.getOrNull())
            return if (clean.isNotBlank()) Narration(clean, true, "unsanitized")
            else Narration(planFallback(facts), false, "empty")
        }
        val gate = NarrationSanitizer.gate(attempt.getOrNull(), NarrationSanitizer.Mode.RATIONALE)
        return gate.text?.let { Narration(it, true) }
            ?: Narration(planFallback(facts), false, "style:${gate.reason}")
    }

    // ---- payloads (aggregates only; no quest titles leave the device) ----

    internal fun reviewPayload(r: ReviewGenerator.Review): String = buildString {
        appendLine("period: ${r.periodLabel}")
        appendLine("completed: ${r.totalCompleted}")
        appendLine("attempted: ${r.totalAttempted}")
        appendLine("completion_rate: ${rate(r.completionRate)}")
        appendLine("active_days: ${r.activeDays}")
        appendLine("xp_earned: ${r.xpEarned}")
        if (r.byCategory.isEmpty()) {
            appendLine("categories: none")
        } else {
            appendLine("categories:")
            r.byCategory.forEach {
                appendLine("- ${it.category.pretty()}: ${it.completed}/${it.attempted} (${rate(it.completionRate)})")
            }
        }
        appendLine("strongest_category: ${r.strongestCategory?.pretty() ?: "none"}")
        append("most_neglected_category: ${r.mostNeglectedCategory?.pretty() ?: "none"}")
    }

    internal fun planPayload(f: PlanFacts): String = buildString {
        appendLine("energy: ${f.energy ?: "unknown"}")
        appendLine("low_energy_day: ${f.lowEnergyDay}")
        appendLine("time_budget_enforced: ${f.timeBudgetEnforced}")
        if (f.timeBudgetEnforced && f.availableMinutes != null) appendLine("available_minutes: ${f.availableMinutes}")
        appendLine("tasks_today: ${f.tasksToday}")
        appendLine("total_minutes: ${f.totalMinutes}")
        if (f.deferredCount > 0) appendLine("deferred_count: ${f.deferredCount}")
        appendLine("category_mix: ${f.categoryMix}")
        if (f.due != "none") appendLine("due: ${f.due}")
        append("notes: ${f.firstNote ?: "none"}")
    }

    private fun rate(v: Double): String = ((v * 100).roundToInt() / 100.0).toString()

    private fun failNote(attempt: Result<String>): String =
        "request:${attempt.exceptionOrNull()?.message?.take(60) ?: "failed"}"

    /** Deterministic fallbacks: terse, factual, slop-free. Public so callers can use them
     *  directly when AI is off (no LLM client needed). */
    companion object {
        fun reviewFallback(r: ReviewGenerator.Review): String {
            if (r.totalAttempted == 0) return "Nothing logged this period."
            val out = StringBuilder("${r.totalCompleted} of ${r.totalAttempted} done over ${r.activeDays} active days.")
            r.strongestCategory?.let { out.append(" Strongest area: ${it.pretty()}.") }
            r.mostNeglectedCategory?.let { cat ->
                val pct = r.byCategory.firstOrNull { it.category == cat }?.let { (it.completionRate * 100).roundToInt() }
                if (pct != null) out.append(" ${cat.pretty()} ran low at $pct%.")
            }
            return out.toString()
        }

        fun planFallback(f: PlanFacts): String = when {
            f.tasksToday == 0 -> "Nothing scheduled today."
            f.lowEnergyDay ->
                "Lighter day on low energy: ${f.tasksToday} smaller things, about ${f.totalMinutes} minutes."
            else -> "${f.tasksToday} on the list today, about ${f.totalMinutes} minutes."
        }
    }
}

private fun QuestCategory.pretty(): String = name.lowercase().replace('_', ' ')
