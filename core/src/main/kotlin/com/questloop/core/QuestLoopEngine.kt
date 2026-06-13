package com.questloop.core

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
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
    ): CompletionEffect {
        val context = deriveContext(newRecord, history)
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

    internal fun deriveContext(record: CompletionRecord, history: List<CompletionRecord>): RewardContext {
        // Anti-farm targets repeating the *same* quest multiple times on the
        // *same day* (the actual exploit). Completing a daily habit across
        // consecutive days is consistency, not farming, and must not be decayed.
        val priorSame = history.count {
            it.questId == record.questId &&
                it.epochDay == record.epochDay &&
                (it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL)
        }
        val sameDay = history.filter { it.epochDay == record.epochDay }
        // We can only know XP already applied today if the caller stored it; we
        // approximate meta/penalty usage by re-scoring same-day records with a
        // neutral context. This stays deterministic and conservative.
        val metaToday = sameDay.filter { it.isMeta }.sumOf {
            rewardEngine.score(it, RewardContext()).xp.coerceAtLeast(0)
        }
        val penaltyToday = sameDay.sumOf {
            val xp = rewardEngine.score(it, RewardContext()).xp
            if (xp < 0) -xp else 0
        }
        val activeDays = history
            .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL }
            .map { it.epochDay }
            .toSet()
        val streak = StreakTracker.currentStreak(activeDays, record.epochDay, streakGraceDays)
        return RewardContext(
            priorSameQuestCompletions = priorSame,
            metaXpEarnedToday = metaToday,
            penaltyXpAppliedToday = penaltyToday,
            currentStreakDays = streak,
        )
    }
}
