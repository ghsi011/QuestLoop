package com.questloop.core.generation

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestInstance
import com.questloop.core.model.UserProfile
import com.questloop.core.model.EnergyCheckIn

/**
 * Builds a balanced daily quest list from a candidate pool (SPEC sections 3 & 4).
 *
 * Pure and deterministic: selection is driven by scoring and explicit budgets
 * rather than randomness, so generation is fully unit-testable and explainable.
 * Freshness/variety comes from category caps and avoidance scoring, not RNG.
 */
class QuestGenerator(
    private val scorer: QuestScorer = QuestScorer(),
) {

    data class Request(
        val epochDay: Long,
        val profile: UserProfile,
        val candidates: List<Quest>,
        val history: List<CompletionRecord> = emptyList(),
        val checkIn: EnergyCheckIn? = null,
        /**
         * System routine quests (morning/evening admin) for the minimal daily
         * loop. Always scheduled first and exempt from the variety/meta caps, but
         * skipped once dismissed for the day.
         */
        val routineQuests: List<Quest> = emptyList(),
        /**
         * Quest ids that should not appear again today (finished, skipped, or a
         * one-shot already done). Computed by the caller with style awareness so
         * partially-logged counting/timed quests can stay visible.
         */
        val dismissedToday: Set<String> = emptySet(),
    )

    data class DailyPlan(
        val epochDay: Long,
        val quests: List<QuestInstance>,
        val totalEstimatedMinutes: Int,
        val deferred: List<Quest>,
        val notes: List<String>,
    )

    fun generateDaily(request: Request): DailyPlan {
        val prefs = request.profile.preferences
        val energy = request.checkIn?.energy
        val availableMinutes = request.checkIn?.availableMinutes ?: prefs.defaultAvailableMinutes

        val notes = mutableListOf<String>()

        // Low-energy days get fewer quests and a difficulty ceiling so the plan
        // is never punishing (SPEC 8 & 9: support low-energy days, no burnout).
        val lowEnergy = energy != null && energy <= 2
        val maxQuests = if (lowEnergy) (prefs.maxDailyQuests - 2).coerceAtLeast(2) else prefs.maxDailyQuests
        val difficultyCeiling = if (lowEnergy) Difficulty.MEDIUM else Difficulty.EPIC
        if (lowEnergy) {
            notes += "Low-energy day detected — suggesting a lighter, shorter plan. Rest is progress too."
        }

        // Score every candidate.
        val scored = request.candidates
            .map { it to scorer.score(it, request) }
            .sortedByDescending { it.second.total }

        val selected = mutableListOf<QuestInstance>()
        var usedMinutes = 0
        val categoryCount = mutableMapOf<QuestCategory, Int>()
        var metaIncluded = 0
        // Cap any single category to keep the day varied (no chore-only days).
        val perCategoryCap = maxOf(2, kotlin.math.ceil(maxQuests * 0.6).toInt())

        // Hard limits that always apply (count, time, difficulty, meta budget).
        fun canAdd(quest: Quest): Boolean {
            if (selected.size >= maxQuests) return false
            if (quest.difficulty.ordinal > difficultyCeiling.ordinal) return false
            if (quest.category.isMeta && metaIncluded >= 1) return false
            if (usedMinutes + quest.estimatedMinutes > availableMinutes && selected.isNotEmpty()) return false
            return true
        }

        fun add(quest: Quest) {
            selected += QuestInstance(
                instanceId = "${quest.id}@${request.epochDay}",
                quest = quest,
                scheduledEpochDay = request.epochDay,
            )
            usedMinutes += quest.estimatedMinutes
            categoryCount[quest.category] = categoryCount.getOrDefault(quest.category, 0) + 1
            if (quest.category.isMeta) metaIncluded++
        }

        // Routine quests form the minimal daily loop: always shown first (unless
        // dismissed today), exempt from the variety/meta caps and the count
        // limit so the backbone is never crowded out.
        val dismissed = request.dismissedToday
        for (routine in request.routineQuests) {
            if (routine.id in dismissed) continue
            if (selected.any { it.quest.id == routine.id }) continue
            add(routine)
        }

        val remaining = scored.map { it.first }
            .filter { it.id !in dismissed }
            .toMutableList()

        // Greedy selection honouring the per-category cap so a single category
        // (e.g. chores) can never dominate the day (SPEC 4: not too repetitive).
        val iter = remaining.iterator()
        while (iter.hasNext()) {
            val quest = iter.next()
            if (!canAdd(quest)) continue
            if (categoryCount.getOrDefault(quest.category, 0) >= perCategoryCap) continue
            add(quest)
            iter.remove()
        }

        val deferred = remaining

        if (selected.isEmpty() && request.candidates.isNotEmpty()) {
            // Always offer at least one approachable quest rather than an empty day.
            val easiest = request.candidates.minByOrNull { it.difficulty.ordinal }!!
            selected += QuestInstance("${easiest.id}@${request.epochDay}", easiest, request.epochDay)
            usedMinutes += easiest.estimatedMinutes
            deferred.remove(easiest)
        }

        if (usedMinutes > availableMinutes) {
            notes += "Plan slightly exceeds your available time — feel free to defer anything."
        }
        if (deferred.size > selected.size && selected.isNotEmpty()) {
            notes += "${deferred.size} quest(s) deferred to keep today realistic."
        }

        return DailyPlan(
            epochDay = request.epochDay,
            quests = selected,
            totalEstimatedMinutes = usedMinutes,
            deferred = deferred,
            notes = notes,
        )
    }
}

/** Exposed separately so scoring weights can be unit-tested in isolation. */
class QuestScorer {

    data class Score(
        val urgency: Double,
        val priority: Double,
        val avoidance: Double,
        val focus: Double,
        val energyFit: Double,
    ) {
        val total: Double get() = urgency + priority + avoidance + focus + energyFit
    }

    fun score(quest: Quest, request: QuestGenerator.Request): Score {
        val urgency = urgencyScore(quest, request.epochDay)
        val priority = quest.priority.multiplier
        val avoidance = avoidanceScore(quest, request.history, request.epochDay)
        val focus = if (quest.category in request.profile.preferences.focusCategories) 0.75 else 0.0
        val energyFit = energyFitScore(quest, request.checkIn)
        return Score(urgency, priority, avoidance, focus, energyFit)
    }

    internal fun urgencyScore(quest: Quest, today: Long): Double {
        val deadline = quest.deadlineEpochDay ?: return 0.5
        val daysUntil = deadline - today
        return when {
            daysUntil < 0 -> 5.0   // overdue
            daysUntil == 0L -> 4.0 // due today
            daysUntil <= 1 -> 3.0
            daysUntil <= 3 -> 2.0
            daysUntil <= 7 -> 1.0
            else -> 0.5
        }
    }

    /**
     * Quests the user has recently skipped/failed get a gentle boost so they
     * resurface (SPEC 4: surface avoidance patterns) — but the boost is capped
     * so avoidance never stacks into a punishing day.
     */
    internal fun avoidanceScore(quest: Quest, history: List<CompletionRecord>, today: Long): Double {
        val windowStart = today - 14
        val avoided = history.count {
            it.questId == quest.id &&
                it.epochDay >= windowStart &&
                (it.result == CompletionResult.SKIPPED || it.result == CompletionResult.FAILED)
        }
        return (avoided * 0.5).coerceAtMost(2.0)
    }

    /** Penalise heavy quests when energy is low; mildly favour them when high. */
    internal fun energyFitScore(quest: Quest, checkIn: EnergyCheckIn?): Double {
        val energy = checkIn?.energy ?: return 0.0
        return when {
            energy <= 2 && quest.difficulty.ordinal >= Difficulty.HARD.ordinal -> -2.0
            energy <= 2 && quest.difficulty == Difficulty.MEDIUM -> -0.5
            energy >= 4 && quest.difficulty.ordinal >= Difficulty.HARD.ordinal -> 0.5
            else -> 0.0
        }
    }
}
