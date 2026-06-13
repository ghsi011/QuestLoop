package com.questloop.app.data

import com.questloop.app.data.local.CompletionDao
import com.questloop.app.data.local.QuestDao
import com.questloop.core.QuestLoopEngine
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.model.VerificationMethod
import com.questloop.core.reward.Achievement
import com.questloop.core.reward.AchievementEngine
import com.questloop.core.reward.ProgressStats
import com.questloop.core.reward.StreakTracker
import com.questloop.core.reward.RewardAllowanceCalculator
import com.questloop.core.review.ReviewGenerator
import com.questloop.core.safety.SafetyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for the UI. Wraps persistence (Room + DataStore) and
 * the pure [QuestLoopEngine]/generators, exposing reactive flows and suspend
 * actions. All economy decisions are delegated to :core.
 */
class QuestRepository(
    private val questDao: QuestDao,
    private val completionDao: CompletionDao,
    private val profileStore: ProfileStore,
    private val engine: QuestLoopEngine = QuestLoopEngine(),
    private val generator: QuestGenerator = QuestGenerator(),
    private val safetyGuard: SafetyGuard = SafetyGuard(),
) {
    val quests: Flow<List<Quest>> = questDao.observeActive().map { list -> list.map { it.toModel() } }

    val completions: Flow<List<CompletionRecord>> =
        completionDao.observeAll().map { list -> list.map { it.toModel() } }

    val profile = profileStore.profile

    suspend fun addQuest(quest: Quest) = questDao.upsert(quest.toEntity())

    suspend fun archiveQuest(id: String) = questDao.archive(id)

    suspend fun seedIfEmpty() {
        if (questDao.count() == 0) {
            SampleData.starterQuests.forEach { questDao.upsert(it.toEntity()) }
        }
    }

    /** Build today's plan from active quests + recent history. */
    suspend fun todayPlan(epochDay: Long, checkIn: EnergyCheckIn? = null): QuestGenerator.DailyPlan {
        val profile = profileStore.profile.first()
        val candidates = questDao.getActive().map { it.toModel() }
            .filter { it.id !in completedQuestIdsToday(epochDay) }
        val history = completionDao.getAll().map { it.toModel() }
        return generator.generateDaily(
            QuestGenerator.Request(
                epochDay = epochDay,
                profile = profile,
                candidates = candidates,
                history = history,
                checkIn = checkIn,
            ),
        )
    }

    private suspend fun completedQuestIdsToday(epochDay: Long): Set<String> =
        completionDao.between(epochDay, epochDay)
            .filter { it.result == CompletionResult.COMPLETED.name || it.result == CompletionResult.PARTIAL.name }
            .map { it.questId }
            .toSet()

    /**
     * Records a completion: scores it via the engine, persists the record, and
     * updates the user's XP. Returns the engine effect so the UI can celebrate
     * level-ups and show the explanation.
     */
    data class CompleteOutcome(
        val effect: QuestLoopEngine.CompletionEffect,
        val newlyUnlocked: List<Achievement>,
    )

    suspend fun completeQuest(
        quest: Quest,
        epochDay: Long,
        result: CompletionResult,
        fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        verification: VerificationMethod = VerificationMethod.MANUAL,
    ): CompleteOutcome {
        val profile = profileStore.profile.first()
        val history = completionDao.getAll().map { it.toModel() }
        val record = CompletionRecord(
            instanceId = "${quest.id}@$epochDay",
            questId = quest.id,
            category = quest.category,
            difficulty = quest.difficulty,
            priority = quest.priority,
            result = result,
            verification = verification,
            epochDay = epochDay,
            fraction = fraction,
            isMeta = quest.category.isMeta,
        )
        val before = statsFrom(history, profile.totalXp)
        val effect = engine.recordCompletion(profile.totalXp, record, history)
        completionDao.insert(record.toEntity(effect.outcome.xp))
        profileStore.setTotalXp(effect.newTotalXp)
        val after = statsFrom(history + record, effect.newTotalXp)
        return CompleteOutcome(effect, AchievementEngine.newlyUnlocked(before, after))
    }

    private fun statsFrom(history: List<CompletionRecord>, totalXp: Long): ProgressStats {
        val active = history
            .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL }
            .map { it.epochDay }.toSet()
        return ProgressStats.from(history, totalXp, StreakTracker.longestStreak(active))
    }

    suspend fun unlockedAchievements(): List<Achievement> {
        val profile = profileStore.profile.first()
        val history = completionDao.getAll().map { it.toModel() }
        return AchievementEngine.unlocked(statsFrom(history, profile.totalXp))
    }

    suspend fun review(periodLabel: String, fromEpochDay: Long, toEpochDay: Long): ReviewGenerator.Review {
        val records = completionDao.between(fromEpochDay, toEpochDay).map { it.toModel() }
        val xpByInstance = completionDao.between(fromEpochDay, toEpochDay)
            .associate { it.instanceId to it.xpAwarded }
        return ReviewGenerator.generate(periodLabel, records) { xpByInstance[it.instanceId] ?: 0L }
    }

    suspend fun allowance(fromEpochDay: Long, toEpochDay: Long): RewardAllowanceCalculator.AllowanceResult {
        val profile = profileStore.profile.first()
        val records = completionDao.between(fromEpochDay, toEpochDay).map { it.toModel() }
        val activeDays = records
            .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL }
            .map { it.epochDay }.toSet().size
        return RewardAllowanceCalculator.calculate(
            RewardAllowanceCalculator.AllowanceInput(
                monthlyBudgetCap = profile.preferences.monthlyRewardBudgetCap,
                records = records,
                activeDays = activeDays,
                daysInMonth = (toEpochDay - fromEpochDay + 1).toInt().coerceAtLeast(1),
            ),
        )
    }

    suspend fun safetySignals(today: Long): List<SafetyGuard.Signal> {
        val records = completionDao.getAll().map { it.toModel() }
        val active = records
            .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.PARTIAL }
            .map { it.epochDay }.toSet()
        return safetyGuard.evaluate(records, active, today)
    }

    /** Combined snapshot used by the Today screen header. */
    fun headerState(): Flow<HeaderState> = combine(profile, completions) { p, comps ->
        HeaderState(totalXp = p.totalXp, totalCompletions = comps.size)
    }

    data class HeaderState(val totalXp: Long, val totalCompletions: Int)

    suspend fun setBudgetCap(value: Double) = profileStore.setBudgetCap(value)
    suspend fun setMaxDaily(value: Int) = profileStore.setMaxDaily(value)
    suspend fun setAvailableMinutes(value: Int) = profileStore.setAvailableMinutes(value)
}
