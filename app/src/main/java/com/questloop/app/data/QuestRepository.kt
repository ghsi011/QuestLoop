package com.questloop.app.data

import com.questloop.app.data.local.CompletionDao
import com.questloop.app.data.local.QuestDao
import com.questloop.core.QuestLoopEngine
import com.questloop.core.completion.CompletionPolicy
import com.questloop.core.completion.CompletionScaling
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.generation.QuestScheduler
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.model.VerificationMethod
import com.questloop.core.reward.Achievement
import com.questloop.core.reward.AchievementEngine
import com.questloop.core.reward.LevelSystem
import com.questloop.core.reward.ProgressStats
import com.questloop.core.reward.RewardAllowanceCalculator
import com.questloop.core.reward.StreakTracker
import com.questloop.core.review.ReviewGenerator
import com.questloop.core.safety.SafetyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Single source of truth for the UI. Wraps persistence (Room + DataStore) and
 * the pure [QuestLoopEngine]/generators.
 *
 * Total XP is derived from the completion ledger (`SUM(xpAwarded)`), so there is
 * a single authority for it: completions are idempotent per `instanceId`, and
 * re-logging an instance replaces its prior grant rather than stacking.
 */
class QuestRepository(
    private val questDao: QuestDao,
    private val completionDao: CompletionDao,
    private val profileStore: ProfilePreferences,
    private val engine: QuestLoopEngine = QuestLoopEngine(),
    private val generator: QuestGenerator = QuestGenerator(),
    private val safetyGuard: SafetyGuard = SafetyGuard(),
) {
    /** Days of history loaded for same-day reward context (cheap, bounded). */
    private val contextWindowDays = 2L

    /** Days of history considered for safety signals. */
    private val safetyWindowDays = 30L

    val quests: Flow<List<Quest>> = questDao.observeActive().map { list -> list.map { it.toModel() } }

    val completions: Flow<List<CompletionRecord>> =
        completionDao.observeAll().map { list -> list.map { it.toModel() } }

    val profile = profileStore.profile

    /** Total XP, derived from the ledger (the single source of truth). */
    val totalXp: Flow<Long> = completionDao.observeTotalXp()

    suspend fun totalXp(): Long = completionDao.totalXp()

    suspend fun addQuest(quest: Quest) = questDao.upsert(quest.toEntity())

    suspend fun archiveQuest(id: String) = questDao.archive(id)

    suspend fun seedIfEmpty() {
        if (questDao.count() == 0) {
            SampleData.starterQuests.forEach { questDao.upsert(it.toEntity()) }
        }
    }

    /** Build today's plan from active quests + recent history + the day's routine. */
    suspend fun todayPlan(
        epochDay: Long,
        dayPart: DayPart,
        checkIn: EnergyCheckIn? = null,
    ): QuestGenerator.DailyPlan {
        val profile = profileStore.profile.first()
        // Only surface quests whose recurrence cadence makes them due today
        // (e.g. a weekly quest doesn't reappear every day after completion).
        val lastCompleted = completionDao.lastCompletedDays().associate { it.questId to it.lastDay }
        val candidates = questDao.getActive().map { it.toModel() }
            .filter { QuestScheduler.isDue(it.frequency, epochDay, lastCompleted[it.id]) }
        // Recent history is only needed for avoidance scoring (skipped quests).
        val history = completionDao.since(epochDay - 14).map { it.toModel() }
        return generator.generateDaily(
            QuestGenerator.Request(
                epochDay = epochDay,
                profile = profile,
                candidates = candidates,
                history = history,
                checkIn = checkIn,
                routineQuests = RoutineQuestFactory.routinesFor(dayPart),
                dismissedToday = dismissedQuestIdsToday(epochDay),
            ),
        )
    }

    /**
     * Quest ids that should not appear again today. Style-aware: a partially
     * logged QUANTITATIVE/DURATION quest is *not* dismissed so the user can keep
     * adding progress.
     */
    private suspend fun dismissedQuestIdsToday(epochDay: Long): Set<String> {
        val styleById = questDao.getActive().associate { it.id to it.toModel().completionStyle }
        return completionDao.between(epochDay, epochDay)
            .map { it.toModel() }
            .filter { rec ->
                val style = styleById[rec.questId] ?: CompletionStyle.BINARY
                CompletionPolicy.dismissedForToday(style, rec.result)
            }
            .map { it.questId }
            .toSet()
    }

    /**
     * Today's logged progress per quest (count for QUANTITATIVE, minutes for
     * DURATION), so the UI can resume partial logging from where it left off.
     */
    suspend fun todayProgress(epochDay: Long): Map<String, Int> {
        val quests = questDao.getActive().map { it.toModel() }.associateBy { it.id }
        return buildMap {
            for (e in completionDao.between(epochDay, epochDay)) {
                val quest = quests[e.questId] ?: continue
                val target = when (quest.completionStyle) {
                    CompletionStyle.QUANTITATIVE -> quest.targetCount ?: continue
                    CompletionStyle.DURATION -> quest.estimatedMinutes
                    else -> continue
                }
                put(quest.id, (e.fraction * target).roundToInt())
            }
        }
    }

    data class CompleteOutcome(
        val effect: QuestLoopEngine.CompletionEffect,
        val newlyUnlocked: List<Achievement>,
    )

    /**
     * Records a completion idempotently. The record is keyed by `instanceId`
     * (`questId@epochDay`); re-logging the same instance replaces its prior grant
     * via the ledger, so XP never double-counts (the prior `xpAwarded` is netted
     * out before the new grant is applied).
     */
    suspend fun completeQuest(
        quest: Quest,
        epochDay: Long,
        result: CompletionResult,
        fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        verification: VerificationMethod = VerificationMethod.MANUAL,
    ): CompleteOutcome {
        val instanceId = "${quest.id}@$epochDay"
        val existing = completionDao.find(instanceId)
        val ledgerSum = completionDao.totalXp()
        // The user's total as if this instance had never been logged.
        val baseline = (ledgerSum - (existing?.xpAwarded ?: 0L)).coerceAtLeast(0L)

        val record = CompletionRecord(
            instanceId = instanceId,
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

        val sameDay = completionDao.between(epochDay, epochDay).map { it.toModel() }
        val activeDays = completionDao.activeDays().toSet()
        val context = engine.contextFrom(record, sameDay, activeDays)

        val before = progressStats(ledgerSum, activeDays)
        val effect = engine.recordCompletion(baseline, record, context)
        completionDao.upsert(record.copy(xpAwarded = effect.outcome.xp).toEntity())

        // Level-up reflects the user's real before/after totals (handles re-logs).
        val newTotal = (baseline + effect.outcome.xp).coerceAtLeast(0L)
        val corrected = effect.copy(
            previousLevel = LevelSystem.levelForXp(ledgerSum),
            newLevel = LevelSystem.levelForXp(newTotal),
            leveledUp = LevelSystem.levelForXp(newTotal) > LevelSystem.levelForXp(ledgerSum),
            newTotalXp = newTotal,
        )
        val after = progressStats(newTotal, completionDao.activeDays().toSet())
        return CompleteOutcome(corrected, AchievementEngine.newlyUnlocked(before, after))
    }

    /**
     * Completes a non-binary quest from a measured value:
     * - QUANTITATIVE: [value] is the running total toward [Quest.targetCount].
     * - DURATION: [value] is minutes toward [Quest.estimatedMinutes].
     * - SUBJECTIVE: [value] is a 1..5 self-rating.
     * For counting/timed quests progress is monotonic (never decreases on re-log)
     * and credited proportionally — never penalised (SPEC §8).
     */
    suspend fun completeMeasured(quest: Quest, epochDay: Long, value: Int): CompleteOutcome {
        val existingProgress = todayProgress(epochDay)[quest.id] ?: 0
        val scaled = when (quest.completionStyle) {
            CompletionStyle.QUANTITATIVE ->
                CompletionScaling.quantitative(maxOf(existingProgress, value), (quest.targetCount ?: 1).coerceAtLeast(1))
            CompletionStyle.DURATION ->
                CompletionScaling.duration(maxOf(existingProgress, value), quest.estimatedMinutes.coerceAtLeast(1))
            CompletionStyle.SUBJECTIVE ->
                CompletionScaling.subjective(value)
            CompletionStyle.BINARY ->
                CompletionScaling.Scaled(CompletionResult.COMPLETED, 1.0)
        }
        val verification = when (quest.completionStyle) {
            CompletionStyle.DURATION -> VerificationMethod.TIMER
            CompletionStyle.QUANTITATIVE -> VerificationMethod.CHECKLIST
            else -> VerificationMethod.MANUAL
        }
        return completeQuest(quest, epochDay, scaled.result, scaled.fraction, verification)
    }

    /** Aggregate progress for achievements, built from cheap DB queries. */
    private suspend fun progressStats(totalXp: Long, activeDays: Set<Long>): ProgressStats = ProgressStats(
        totalCompleted = completionDao.countCompleted(),
        level = LevelSystem.levelForXp(totalXp),
        longestStreak = StreakTracker.longestStreak(activeDays),
        distinctCategories = completionDao.countDistinctCompletedCategories(),
        honestyLogs = completionDao.countHonestyLogs(),
        reductionWins = completionDao.countReductionWins(),
    )

    suspend fun unlockedAchievements(): List<Achievement> =
        AchievementEngine.unlocked(progressStats(completionDao.totalXp(), completionDao.activeDays().toSet()))

    suspend fun review(periodLabel: String, fromEpochDay: Long, toEpochDay: Long): ReviewGenerator.Review {
        val rows = completionDao.between(fromEpochDay, toEpochDay)
        val xpByInstance = rows.associate { it.instanceId to it.xpAwarded }
        val records = rows.map { it.toModel() }
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
        val records = completionDao.since(today - safetyWindowDays).map { it.toModel() }
        val active = completionDao.activeDays().toSet()
        return safetyGuard.evaluate(records, active, today)
    }

    suspend fun setBudgetCap(value: Double) = profileStore.setBudgetCap(value)
    suspend fun setMaxDaily(value: Int) = profileStore.setMaxDaily(value)
    suspend fun setAvailableMinutes(value: Int) = profileStore.setAvailableMinutes(value)
}
