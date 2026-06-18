package com.questloop.core

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.QuestCategory
import com.questloop.core.reward.LevelSystem
import com.questloop.core.reward.RewardConfig
import com.questloop.core.reward.RewardContext
import com.questloop.core.reward.RewardEngine
import com.questloop.core.reward.RewardOutcome
import com.questloop.core.reward.StreakTracker

/**
 * High-level facade the app layer talks to. It derives the [RewardContext] from
 * stored history so the UI/repository never has to understand the economy's
 * internals — it just submits a [CompletionRecord] and gets back XP + level
 * changes + an explanation.
 *
 * Pure and stateless apart from the config; all state is passed in. This keeps
 * it trivially testable and lets the app own persistence.
 */
class QuestLoopEngine(
    rewardConfig: RewardConfig = RewardConfig(),
    private val streakGraceDays: Int = 1,
) {
    private val rewardEngine = RewardEngine(rewardConfig)

    data class CompletionEffect(
        val outcome: RewardOutcome,
        val newTotalXp: Long,
        val previousLevel: Int,
        val newLevel: Int,
        val leveledUp: Boolean,
    )

    /**
     * Score a new completion against prior history and current XP.
     *
     * @param previousTotalXp the user's XP before this completion.
     * @param newRecord the completion being recorded.
     * @param history all prior completion records (used to derive context).
     */
    fun recordCompletion(
        previousTotalXp: Long,
        newRecord: CompletionRecord,
        history: List<CompletionRecord>,
    ): CompletionEffect = recordCompletion(previousTotalXp, newRecord, deriveContext(newRecord, history))

    /**
     * Score a completion against an already-computed [RewardContext]. Lets a
     * caller build the context cheaply (e.g. from aggregate DB queries) instead
     * of materialising the full history.
     */
    fun recordCompletion(
        previousTotalXp: Long,
        newRecord: CompletionRecord,
        context: RewardContext,
    ): CompletionEffect {
        val outcome = rewardEngine.score(newRecord, context)
        val newTotal = (previousTotalXp + outcome.xp).coerceAtLeast(0)
        val prevLevel = LevelSystem.levelForXp(previousTotalXp)
        val newLevel = LevelSystem.levelForXp(newTotal)
        return CompletionEffect(
            outcome = outcome,
            newTotalXp = newTotal,
            previousLevel = prevLevel,
            newLevel = newLevel,
            leveledUp = newLevel > prevLevel,
        )
    }

    /**
     * Builds a [RewardContext] from the pieces the repository can fetch cheaply:
     * the *same-day* records (for anti-farm and per-day caps) and the set of all
     * active days (for the streak). Pure and unit-testable.
     */
    fun contextFrom(
        record: CompletionRecord,
        sameDayRecords: List<CompletionRecord>,
        activeEpochDays: Set<Long>,
    ): RewardContext {
        val others = sameDayRecords.filter { it.instanceId != record.instanceId }
        val priorSame = others.count { it.questId == record.questId && it.countsAsActivity }
        val metaToday = others.filter { it.isMeta }.sumOf { it.xpAwarded.coerceAtLeast(0) }
        val penaltyToday = others.sumOf { if (it.xpAwarded < 0) -it.xpAwarded else 0L }
        // Honesty XP already granted today (positive grants on bad-habit relapses).
        val honestyToday = others
            .filter {
                it.category == QuestCategory.BAD_HABIT_REDUCTION &&
                    (it.result == CompletionResult.SKIPPED || it.result == CompletionResult.FAILED)
            }
            .sumOf { it.xpAwarded.coerceAtLeast(0) }
        // XP from low-effort (trivial/easy) quests already granted today. Meta
        // maintenance is governed by its own daily cap, so exclude it here or it
        // would double-count against the low-effort allowance and under-reward
        // genuine easy quests.
        val lowEffortToday = others
            .filter { it.countsAsActivity && it.difficulty.isLowEffort && !it.isMeta }
            .sumOf { it.xpAwarded.coerceAtLeast(0) }
        // The streak is the run of active days *leading up to* today; excluding
        // today keeps it stable whether this is the first log or a re-log, so
        // re-logging the same instance can't change its reward (idempotency).
        val streak = StreakTracker.currentStreak(
            activeEpochDays - record.epochDay,
            record.epochDay,
            streakGraceDays,
        )
        return RewardContext(
            priorSameQuestCompletions = priorSame,
            metaXpEarnedToday = metaToday,
            penaltyXpAppliedToday = penaltyToday,
            honestyXpEarnedToday = honestyToday,
            lowEffortXpEarnedToday = lowEffortToday,
            currentStreakDays = streak,
        )
    }

    internal fun deriveContext(record: CompletionRecord, history: List<CompletionRecord>): RewardContext {
        val sameDay = history.filter { it.epochDay == record.epochDay }
        val activeDays = history
            .filter { it.countsAsActivity }
            .map { it.epochDay }
            .toSet()
        return contextFrom(record, sameDay, activeDays)
    }
}
