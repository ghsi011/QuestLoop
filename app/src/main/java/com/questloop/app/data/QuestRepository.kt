package com.questloop.app.data

import com.questloop.app.data.local.CompletionDao
import com.questloop.app.data.local.QuestDao
import com.questloop.core.QuestLoopEngine
import com.questloop.core.ai.AiNarrator
import com.questloop.core.ai.AiQuestService
import com.questloop.core.completion.CompletionPolicy
import com.questloop.core.completion.CompletionScaling
import com.questloop.core.generation.HabitQuestFactory
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.generation.QuestScheduler
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.BadHabit
import com.questloop.core.model.Habit
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val aiDiagnostics: AiDiagnostics = NoopAiDiagnostics,
    private val aiCallGuard: AiCallGuard = NoopAiCallGuard,
) {
    private val exportJson = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

    // Serialises the ledger read-modify-write so concurrent completions (double
    // taps, quest + measured log) can't read the same XP snapshot and mis-count.
    private val completionMutex = Mutex()

    /** Days of history considered for safety signals. */
    private val safetyWindowDays = 30L

    val quests: Flow<List<Quest>> =
        questDao.observeActive().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    val completions: Flow<List<CompletionRecord>> =
        completionDao.observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    val profile = profileStore.profile

    /**
     * Total XP, derived from the ledger (the single source of truth). Floored at
     * zero for display — a gentle miss penalty can momentarily dip the raw ledger
     * below zero, but the user-facing total never shows negative.
     */
    val totalXp: Flow<Long> = completionDao.observeTotalXp().map { it.coerceAtLeast(0) }.distinctUntilChanged()

    suspend fun totalXp(): Long = completionDao.totalXp().coerceAtLeast(0)

    suspend fun addQuest(quest: Quest) = questDao.upsert(quest.toEntity())

    suspend fun archiveQuest(id: String) = questDao.archive(id)

    /** A user-managed quest plus where it stands relative to today. */
    data class QuestStatus(
        val quest: Quest,
        /** In today's curated plan (visible on the Today screen). */
        val inTodaysPlan: Boolean,
        /** Eligible to be done today by its recurrence cadence. */
        val dueToday: Boolean,
        /** Finished for today (binary completed, or a counting/timed target reached). */
        val done: Boolean,
        /** Today's logged count/minutes, for resuming a partial log. */
        val progress: Int,
    )

    /**
     * The full backlog of user-managed quests with each one's status for today.
     * Powers the Quests screen so nothing the user created (or AI suggested) is
     * ever hidden behind the curated daily plan. Habit-derived quests are excluded
     * here because they're managed on the Habits screen, not individually.
     */
    suspend fun questOverview(epochDay: Long, dayPart: DayPart): List<QuestStatus> {
        val checkIn = todayCheckIn(epochDay)
        val plan = todayPlan(epochDay, dayPart, checkIn)
        val inPlan = plan.quests.map { it.quest.id }.toSet()
        val lastCompleted = completionDao.lastCompletedDays().associate { it.questId to it.lastDay }
        // "Done" uses the same style-aware dismissal as the plan: a partially logged
        // counting/timed quest is NOT done, so it stays visible to keep logging.
        val dismissed = dismissedQuestIdsToday(epochDay)
        val progress = todayProgress(epochDay)
        return questDao.getActive().map { it.toModel() }.map { quest ->
            QuestStatus(
                quest = quest,
                inTodaysPlan = quest.id in inPlan,
                dueToday = QuestScheduler.isDue(quest.frequency, epochDay, lastCompleted[quest.id]),
                done = quest.id in dismissed,
                progress = progress[quest.id] ?: 0,
            )
        }
    }

    suspend fun seedIfEmpty() {
        if (questDao.count() == 0) {
            SampleData.onboardingQuests.forEach { questDao.upsert(it.toEntity()) }
        }
    }

    /** Active quest ids (used to show what's already added from the Quest Bank). */
    suspend fun activeQuestIds(): Set<String> = questDao.getActive().map { it.id }.toSet()

    /**
     * Adds a Quest Bank entry by its stable id (re-adding un-archives it) and
     * completes the "Pick your first quest" guide so the user earns its XP.
     */
    suspend fun addFromBank(quest: Quest, epochDay: Long) {
        questDao.upsert(quest.toEntity())
        completeOnboardingQuest(SampleData.ONBOARDING_PICK, epochDay)
    }

    /**
     * Completes a first-run guide quest (awarding its XP) and then archives it so
     * it's gone for good. No-op if it's already been completed/archived.
     */
    suspend fun completeOnboardingQuest(id: String, epochDay: Long) {
        val quest = questDao.getActive().firstOrNull { it.id == id }?.toModel() ?: return
        completeQuest(quest, epochDay, CompletionResult.COMPLETED)
        questDao.archive(id)
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
        // User-created quests plus quests derived from habits, bad habits & goals.
        val derived = HabitQuestFactory.deriveAll(profile.habits, profile.badHabits, profile.goals)
        val candidates = (questDao.getActive().map { it.toModel() } + derived)
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
     * Every quest that can appear in a plan, keyed by id: stored quests plus the
     * ones derived from habits/bad-habits/goals. Used wherever a quest's style or
     * fields are needed, so derived (non-stored) quests aren't silently treated as
     * binary or skipped for progress.
     */
    private suspend fun candidateQuestsById(): Map<String, Quest> {
        val profile = profileStore.profile.first()
        val derived = HabitQuestFactory.deriveAll(profile.habits, profile.badHabits, profile.goals)
        return (questDao.getActive().map { it.toModel() } + derived).associateBy { it.id }
    }

    /**
     * Quest ids that should not appear again today. Style-aware: a partially
     * logged QUANTITATIVE/DURATION quest is *not* dismissed so the user can keep
     * adding progress.
     */
    private suspend fun dismissedQuestIdsToday(epochDay: Long): Set<String> {
        val styleById = candidateQuestsById().mapValues { it.value.completionStyle }
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
        val quests = candidateQuestsById()
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
        /** Identifiers to reverse this action (snackbar "Undo"). */
        val instanceId: String,
        val previousRecord: CompletionRecord?,
    )

    /**
     * Reverses a completion: restores the prior record if there was one (e.g. a
     * partial log), otherwise removes it. XP follows automatically because it's
     * derived from the ledger.
     */
    suspend fun undoCompletion(instanceId: String, previous: CompletionRecord?) = completionMutex.withLock {
        if (previous != null) completionDao.upsert(previous.toEntity()) else completionDao.delete(instanceId)
    }

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
    ): CompleteOutcome = completionMutex.withLock {
        completeQuestLocked(quest, epochDay, result, fraction, verification)
    }

    /** Caller MUST hold [completionMutex]. */
    private suspend fun completeQuestLocked(
        quest: Quest,
        epochDay: Long,
        result: CompletionResult,
        fraction: Double,
        verification: VerificationMethod,
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
        return CompleteOutcome(
            effect = corrected,
            newlyUnlocked = AchievementEngine.newlyUnlocked(before, after),
            instanceId = instanceId,
            previousRecord = existing?.toModel(),
        )
    }

    /**
     * Completes a non-binary quest from a measured value:
     * - QUANTITATIVE: [value] is the running total toward [Quest.targetCount].
     * - DURATION: [value] is minutes toward [Quest.estimatedMinutes].
     * - SUBJECTIVE: [value] is a 1..5 self-rating.
     * For counting/timed quests progress is monotonic (never decreases on re-log)
     * and credited proportionally — never penalised (SPEC §8).
     */
    suspend fun completeMeasured(quest: Quest, epochDay: Long, value: Int): CompleteOutcome = completionMutex.withLock {
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
        completeQuestLocked(quest, epochDay, scaled.result, scaled.fraction, verification)
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

    /** Current streak (consecutive active days up to today), honouring grace days. */
    suspend fun currentStreak(today: Long): Int {
        val grace = profileStore.profile.first().preferences.streakGraceDays
        return StreakTracker.currentStreak(completionDao.activeDays().toSet(), today, grace)
    }

    data class AchievementStatus(val achievement: Achievement, val unlocked: Boolean)

    /** All achievements with their unlocked state, for the achievements screen. */
    suspend fun achievementStatuses(): List<AchievementStatus> {
        val unlocked = unlockedAchievements().map { it.id }.toSet()
        return AchievementEngine.ALL.map { AchievementStatus(it, it.id in unlocked) }
    }

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
    suspend fun setFocusCategories(cats: Set<com.questloop.core.model.QuestCategory>) =
        profileStore.setFocusCategories(cats)

    suspend fun addHabit(habit: Habit) {
        val current = profileStore.profile.first().habits
        profileStore.setHabits(current.filterNot { it.id == habit.id } + habit)
    }

    suspend fun removeHabit(id: String) {
        profileStore.setHabits(profileStore.profile.first().habits.filterNot { it.id == id })
    }

    suspend fun addBadHabit(badHabit: BadHabit) {
        val current = profileStore.profile.first().badHabits
        profileStore.setBadHabits(current.filterNot { it.id == badHabit.id } + badHabit)
    }

    suspend fun removeBadHabit(id: String) {
        profileStore.setBadHabits(profileStore.profile.first().badHabits.filterNot { it.id == id })
    }

    suspend fun addGoal(goal: com.questloop.core.model.Goal) {
        val current = profileStore.profile.first().goals
        profileStore.setGoals(current.filterNot { it.id == goal.id } + goal)
    }

    suspend fun removeGoal(id: String) {
        profileStore.setGoals(profileStore.profile.first().goals.filterNot { it.id == id })
    }

    /** Serialises all on-device data to JSON for export (SPEC §9 portability). */
    suspend fun exportJson(): String {
        // Include archived quests so export is a complete backup, not just active data.
        val all = questDao.getAll().map { it.toModel() to it.archived }
        val snapshot = ExportSnapshot(
            quests = all.map { it.first },
            completions = completionDao.all().map { it.toModel() },
            profile = profileStore.profile.first(),
            archivedIds = all.filter { it.second }.map { it.first.id },
        )
        return exportJson.encodeToString(ExportSnapshot.serializer(), snapshot)
    }

    data class ImportResult(val quests: Int, val completions: Int, val skipped: Int = 0, val error: String? = null)

    /**
     * Restores a previously [exportJson]ed snapshot (SPEC §9 portability). Merge,
     * not wipe: quests upsert by id, completions upsert idempotently by instanceId
     * (so re-importing the same file changes nothing and XP — derived from the
     * ledger — is restored automatically), profile lists union by id, and archived
     * quests are re-archived. The API key is never in a snapshot, so it's untouched.
     *
     * Completions whose quest can't be accounted for (not in the snapshot, the
     * current quests, the derived habit/goal quests, or a system routine) are
     * dropped, so a hand-edited file can't inject phantom XP. Rejects malformed
     * JSON and snapshots from a newer app version. Runs off the main thread; a
     * cancelled import is harmless because re-import is idempotent.
     */
    suspend fun importJson(json: String): ImportResult = withContext(Dispatchers.IO) {
        val snapshot = runCatching { exportJson.decodeFromString(ExportSnapshot.serializer(), json) }.getOrNull()
            ?: return@withContext ImportResult(0, 0, error = "That file isn't a QuestLoop backup.")
        if (snapshot.version > ExportSnapshot.CURRENT_VERSION) {
            return@withContext ImportResult(
                0, 0,
                error = "This backup is from a newer version of QuestLoop. Update the app first.",
            )
        }

        // Merge profile first so derived (habit/goal) quest ids are known.
        val current = profileStore.profile.first()
        val prefs = snapshot.profile.preferences
        val habits = mergeById(current.habits, snapshot.profile.habits) { it.id }
        val badHabits = mergeById(current.badHabits, snapshot.profile.badHabits) { it.id }
        val goals = mergeById(current.goals, snapshot.profile.goals) { it.id }
        profileStore.setHabits(habits)
        profileStore.setBadHabits(badHabits)
        profileStore.setGoals(goals)
        profileStore.setMaxDaily(prefs.maxDailyQuests)
        profileStore.setAvailableMinutes(prefs.defaultAvailableMinutes)
        profileStore.setBudgetCap(prefs.monthlyRewardBudgetCap)
        profileStore.setFocusCategories(prefs.focusCategories)

        // The set of quest ids a completion may legitimately reference.
        val derivedIds = HabitQuestFactory.deriveAll(habits, badHabits, goals).map { it.id }
        val routineIds = RoutineQuestFactory.all().map { it.id }
        val existingIds = questDao.getAll().map { it.id }
        val validQuestIds = (snapshot.quests.map { it.id } + existingIds + derivedIds + routineIds).toSet()

        var imported = 0
        var skipped = 0
        completionMutex.withLock {
            snapshot.quests.forEach { questDao.upsert(it.toEntity()) }
            snapshot.archivedIds.forEach { questDao.archive(it) }
            snapshot.completions.forEach { record ->
                if (record.questId in validQuestIds) {
                    completionDao.upsert(record.toEntity())
                    imported++
                } else {
                    skipped++
                }
            }
        }
        ImportResult(quests = snapshot.quests.size, completions = imported, skipped = skipped)
    }

    /** Union by stable id; the incoming (imported) item wins on a collision. */
    private fun <T> mergeById(existing: List<T>, incoming: List<T>, id: (T) -> String): List<T> {
        val byId = LinkedHashMap<String, T>()
        existing.forEach { byId[id(it)] = it }
        incoming.forEach { byId[id(it)] = it }
        return byId.values.toList()
    }

    /** Today's persisted energy check-in, or null if none was made today. */
    suspend fun todayCheckIn(epochDay: Long): EnergyCheckIn? =
        profileStore.getCheckIn()?.takeIf { it.epochDay == epochDay }

    suspend fun setCheckIn(checkIn: EnergyCheckIn) = profileStore.setCheckIn(checkIn)

    suspend fun aiConfig(): AiConfig = profileStore.getAiConfig()
    suspend fun setAiConfig(config: AiConfig) = profileStore.setAiConfig(config)

    /** Recent AI error log (model + reason), for the user to export when AI misbehaves. */
    suspend fun aiDiagnosticsDump(): String = withContext(Dispatchers.IO) { aiDiagnostics.dump() }
    suspend fun clearAiDiagnostics() = withContext(Dispatchers.IO) { aiDiagnostics.clear() }

    suspend fun isOnboardingComplete(): Boolean = profileStore.isOnboardingComplete()
    suspend fun completeOnboarding() = profileStore.setOnboardingComplete()

    suspend fun reminderConfig(): ReminderConfig = profileStore.getReminderConfig()
    suspend fun setReminderConfig(config: ReminderConfig) = profileStore.setReminderConfig(config)

    /**
     * Suggests quests from free-text todos using the configured AI provider,
     * routed through [AiQuestService] (guardrails + deterministic fallback). If
     * AI isn't configured, falls back to the deterministic suggester so the
     * feature always returns something safe. The returned quests are not yet
     * persisted — the caller decides which to keep.
     */
    suspend fun suggestQuests(todos: List<String>): AiQuestService.Suggestion {
        val profile = profileStore.profile.first()
        val config = profileStore.getAiConfig()
        val input = AiQuestService.Input(
            todos = todos,
            goals = profile.goals.map { it.title },
            focusAreas = profile.preferences.focusCategories.toList(),
            availableMinutes = profile.preferences.defaultAvailableMinutes,
            // Dedup AI output against quests the user already has.
            existing = questDao.getActive().map { it.toModel() },
        )
        return if (config.usable) {
            // Hold a CPU wake lock so a slow response isn't dropped if the screen sleeps.
            val suggestion = aiCallGuard.keepAwake {
                AiQuestService(OpenRouterClient(config.apiKey, config.model)).suggest(input)
            }
            // Record real failures so the user can export them for troubleshooting.
            suggestion.error?.let { recordAiError(config.model, it) }
            suggestion
        } else {
            // No AI configured: deterministic, always-safe suggestions.
            AiQuestService.Suggestion(
                quests = com.questloop.core.ai.FallbackSuggester.suggest(todos, input.focusAreas.toSet()),
                fromAi = false,
            )
        }
    }

    /**
     * Breaks one goal into a short ladder of reviewable quests via the configured
     * AI provider (guardrails + dedup against existing quests). When AI is off,
     * returns a single deterministic starter step so the feature always does
     * something. Not persisted — the caller reviews before saving.
     */
    suspend fun decomposeGoal(goal: String): AiQuestService.Suggestion {
        val config = profileStore.getAiConfig()
        return if (config.usable) {
            val existing = questDao.getActive().map { it.toModel() }
            val result = aiCallGuard.keepAwake {
                AiQuestService(OpenRouterClient(config.apiKey, config.model)).decomposeGoal(goal, existing)
            }
            result.error?.let { recordAiError(config.model, it) }
            result
        } else {
            AiQuestService.Suggestion(
                quests = com.questloop.core.ai.FallbackSuggester.suggest(listOf("Make a start on: ${goal.trim()}"), emptySet()),
                fromAi = false,
            )
        }
    }

    /**
     * Revises a single not-yet-saved suggestion per the user's instruction via the
     * configured AI provider. Returns an error reason when AI is off or the call
     * fails (the caller keeps the original quest).
     */
    suspend fun refineQuest(quest: Quest, instruction: String): AiQuestService.RefineResult {
        val config = profileStore.getAiConfig()
        if (!config.usable) {
            return AiQuestService.RefineResult(null, "AI is off — turn it on in Settings to refine quests.")
        }
        val result = aiCallGuard.keepAwake {
            AiQuestService(OpenRouterClient(config.apiKey, config.model)).refine(quest, instruction)
        }
        result.error?.let { recordAiError(config.model, it) }
        return result
    }

    /**
     * A short, human summary of a period — AI-narrated when AI is configured,
     * otherwise a terse factual line. AI output is gated by [NarrationSanitizer]
     * (no slop), and only aggregates leave the device (no quest titles).
     */
    suspend fun narrateReview(review: ReviewGenerator.Review): AiNarrator.Narration {
        val config = profileStore.getAiConfig()
        if (!config.usable) return AiNarrator.Narration(AiNarrator.reviewFallback(review), fromAi = false)
        val result = aiCallGuard.keepAwake {
            AiNarrator(OpenRouterClient(config.apiKey, config.model))
                .narrateReview(review, sanitize = config.filterWording)
        }
        // Record any AI miss (transport, style reject, or empty) so misbehaviour is
        // visible in the exportable log — not just transport failures.
        if (!result.fromAi && result.note != null) recordAiError(config.model, "review narration: ${result.note}")
        return result
    }

    /**
     * One line explaining the shape of today's plan (energy/time/deadlines),
     * AI-narrated when AI is on, otherwise a terse factual line.
     */
    suspend fun planRationale(
        plan: QuestGenerator.DailyPlan,
        checkIn: EnergyCheckIn?,
        epochDay: Long,
    ): AiNarrator.Narration {
        val profile = profileStore.profile.first()
        val availableMinutes = checkIn?.availableMinutes ?: profile.preferences.defaultAvailableMinutes
        val facts = AiNarrator.PlanFacts.from(plan, checkIn, availableMinutes, epochDay)
        val config = profileStore.getAiConfig()
        if (!config.usable) return AiNarrator.Narration(AiNarrator.planFallback(facts), fromAi = false)
        val result = aiCallGuard.keepAwake {
            AiNarrator(OpenRouterClient(config.apiKey, config.model))
                .rationale(facts, sanitize = config.filterWording)
        }
        if (!result.fromAi && result.note != null) recordAiError(config.model, "plan rationale: ${result.note}")
        return result
    }

    /** AI errors are logged off the main thread (the diagnostics file is read+rewritten). */
    private suspend fun recordAiError(model: String, message: String) =
        withContext(Dispatchers.IO) { aiDiagnostics.record(model, message) }

    /** Erases all on-device data (SPEC §9: users can delete their data). */
    suspend fun deleteAllData() {
        completionDao.clear()
        questDao.clear()
        profileStore.clear()
    }
}
