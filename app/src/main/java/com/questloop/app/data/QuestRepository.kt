package com.questloop.app.data

import com.questloop.app.data.local.CompletionDao
import com.questloop.app.data.local.QuestDao
import com.questloop.core.QuestLoopEngine
import com.questloop.core.ai.AiNarrator
import com.questloop.core.ai.AiQuestService
import com.questloop.core.completion.CompletionPolicy
import com.questloop.core.completion.CompletionScaling
import com.questloop.core.completion.CompletionSlots
import com.questloop.core.generation.AdminFundFactory
import com.questloop.core.generation.HabitQuestFactory
import com.questloop.core.generation.PeriodPlanner
import java.time.DayOfWeek
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.generation.QuestScheduler
import com.questloop.core.generation.RewardFundState
import com.questloop.core.generation.RoutineQuestFactory
import com.questloop.core.model.BadHabit
import com.questloop.core.model.Habit
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.DayPart
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.model.UserProfile
import com.questloop.core.model.VerificationMethod
import com.questloop.core.reward.Achievement
import com.questloop.core.reward.AchievementEngine
import com.questloop.core.reward.LevelSystem
import com.questloop.core.reward.ProgressStats
import com.questloop.core.reward.RewardAllowanceCalculator
import com.questloop.core.reward.StreakTracker
import com.questloop.core.review.ReviewGenerator
import com.questloop.core.safety.SafetyGuard
import kotlinx.coroutines.CoroutineDispatcher
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
    private val periodPlanner: PeriodPlanner = PeriodPlanner(),
    private val calendarReader: CalendarReader = NoopCalendarReader,
    private val safetyGuard: SafetyGuard = SafetyGuard(),
    private val aiDiagnostics: AiDiagnostics = NoopAiDiagnostics,
    private val aiCallGuard: AiCallGuard = NoopAiCallGuard,
    private val openAiAuth: OpenAiAuth = OpenAiAuthService(),
    // Injectable so tests can point the OpenAI client at a local MockWebServer.
    private val openAiResponsesEndpoint: String = com.questloop.core.ai.openai.OpenAiOAuth.API_RESPONSES_URL,
    // Injectable so ViewModel tests can keep the completed-history mapping inline (synchronous).
    private val historyDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val exportJson = kotlinx.serialization.json.Json { prettyPrint = true; ignoreUnknownKeys = true }

    // Serialises the ledger read-modify-write so concurrent completions (double
    // taps, quest + measured log) can't read the same XP snapshot and mis-count.
    private val completionMutex = Mutex()

    // Serialises profile-list read-modify-writes (habits/bad-habits/goals) and the
    // import merge so a concurrent edit + import can't clobber one another on a
    // stale snapshot (DataStore writes are atomic, but our read-then-write isn't).
    private val profileMutex = Mutex()

    // Serialises the OpenAI OAuth refresh + persist so two concurrent AI calls
    // can't both refresh and invalidate each other's (rotating) refresh token.
    // Also taken by disconnectOpenAi/deleteAllData so an in-flight refresh can't
    // re-persist credentials the user just cleared.
    private val aiAuthMutex = Mutex()

    // Bumped (under aiAuthMutex) whenever credentials are deliberately cleared
    // (sign-out / delete-all). connectOpenAi snapshots it before the browser
    // handshake; the sign-in's onTokens persist aborts if it advanced meanwhile,
    // so a sign-in that finishes AFTER a wipe can't resurrect credentials.
    private var credentialClearGeneration = 0L

    /** Days of history considered for safety signals. */
    private val safetyWindowDays = 30L

    val quests: Flow<List<Quest>> =
        questDao.observeActive().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    val completions: Flow<List<CompletionRecord>> =
        completionDao.observeAll().map { list -> list.map { it.toModel() } }.distinctUntilChanged()

    /**
     * Signal-only companion to [completions] for observers that just need to know
     * "the ledger changed" (e.g. the widget refresher): each write re-runs a cheap
     * MAX(rowid) stamp query instead of re-reading and re-mapping the whole table.
     * Deliberately NOT distinct — the stamp can survive a write unchanged (e.g.
     * undoing a non-latest completion deletes a lower rowid), and every write must
     * still tick.
     */
    val completionsChanged: Flow<Unit> = completionDao.observeChangeStamp().map { }

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
        /** The current interval's logged count/minutes, for resuming a partial log. */
        val progress: Int,
    )

    /**
     * The full backlog of user-managed quests with each one's status for today.
     * Powers the Quests screen so nothing the user created (or AI suggested) is
     * ever hidden behind the curated daily plan. Habit-derived quests are excluded
     * here because they're managed on the Habits screen, not individually.
     */
    suspend fun questOverview(epochDay: Long, dayPart: DayPart): List<QuestStatus> {
        // One DayContext for the whole screen: the plan, the style-aware dismissal
        // set, and the progress map all derive from the same profile/candidate/ledger
        // snapshot instead of each re-reading the profile and issuing a find() per quest.
        val ctx = buildDayContext(epochDay)
        val plan = planFrom(ctx, dayPart, todayCheckIn(epochDay))
        val inPlan = plan.quests.map { it.quest.id }.toSet()
        // "Done" uses the same style-aware dismissal as the plan: a partially logged
        // counting/timed quest is NOT done, so it stays visible to keep logging.
        val dismissed = dismissedQuestIds(ctx)
        val progress = progressByQuest(ctx)
        return ctx.stored.map { quest ->
            QuestStatus(
                quest = quest,
                inTodaysPlan = quest.id in inPlan,
                // Measured weekly/monthly quests are "due" any day of their interval
                // until the target is hit (mirrors the plan's interval-based gate);
                // everything else follows the rolling cadence window.
                dueToday = if (CompletionSlots.hasCalendarInterval(quest)) quest.id !in dismissed
                else QuestScheduler.isDue(quest.frequency, epochDay, ctx.lastCompleted[quest.id]),
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

    /**
     * Build today's plan from active quests + recent history + the day's routine.
     *
     * The day's persisted energy check-in shapes the plan (size, difficulty
     * ceiling, time budget), so it's resolved here rather than by each caller —
     * the widget and reminder receiver see the same plan as the Today screen.
     * Pass [checkIn] only to override the persisted one.
     */
    suspend fun todayPlan(
        epochDay: Long,
        dayPart: DayPart,
        checkIn: EnergyCheckIn? = null,
    ): QuestGenerator.DailyPlan =
        planFrom(buildDayContext(epochDay), dayPart, checkIn ?: todayCheckIn(epochDay))

    /**
     * Builds the plan from an already-assembled [DayContext], so a caller that also
     * needs the dismissal/progress maps ([questOverview]) shares one profile +
     * candidate + ledger snapshot instead of recomputing it.
     */
    private suspend fun planFrom(
        ctx: DayContext,
        dayPart: DayPart,
        resolvedCheckIn: EnergyCheckIn?,
    ): QuestGenerator.DailyPlan {
        val profile = ctx.profile
        // Only surface quests whose recurrence cadence makes them due today
        // (e.g. a weekly quest doesn't reappear every day after completion).
        val lastCompleted = ctx.lastCompleted
        // Style-aware "already done for now" set — computed once and reused for both
        // the candidate filter and the generator request.
        val dismissed = dismissedQuestIds(ctx)
        // User-created quests plus quests derived from habits/goals and the
        // reward-fund admin flow.
        val derived = ctx.derivedHabits + adminFundQuests(ctx.epochDay, profile, lastCompleted)
        val candidates = (ctx.stored + derived)
            .filter {
                // Measured weekly/monthly quests reset on their calendar interval, so
                // their visibility is governed by interval dismissal (target reached
                // this week/month) — NOT the rolling isDue window, which keys off the
                // last completion day and would otherwise keep a fresh interval hidden
                // for up to a period. Everything else uses the normal cadence gate.
                if (CompletionSlots.hasCalendarInterval(it)) it.id !in dismissed
                else QuestScheduler.isDue(it.frequency, ctx.epochDay, lastCompleted[it.id])
            }
        // Recent history is only needed for avoidance scoring (skipped quests).
        val history = completionDao.since(ctx.epochDay - 14).map { it.toModel() }
        // Free time left on the device calendar (null unless the user opted in and
        // granted permission) tightens today's time budget automatically.
        val calendarMinutes = calendarReader.freeMinutesToday()
        return generator.generateDaily(
            QuestGenerator.Request(
                epochDay = ctx.epochDay,
                profile = profile,
                candidates = candidates,
                history = history,
                checkIn = resolvedCheckIn,
                routineQuests = RoutineQuestFactory.routinesFor(dayPart),
                dismissedToday = dismissed,
                availableMinutesOverride = calendarMinutes,
            ),
        )
    }

    /**
     * Admin quests for the self-funded reward pot (SPEC §6), derived from the
     * current budget, whether the user has opened a pot (a completed one-off in
     * the ledger), and this month's earned allowance. Returns nothing until a
     * budget is set — the Rewards screen prompts for that instead.
     */
    private suspend fun adminFundQuests(
        today: Long,
        profile: UserProfile,
        lastCompleted: Map<String, Long>,
    ): List<Quest> {
        val cap = profile.preferences.monthlyRewardBudgetCap
        if (cap <= 0.0) return emptyList()
        val potOpened = lastCompleted.containsKey(AdminFundFactory.OPEN_POT_ID)
        val monthStart = CompletionSlots.startOfMonth(today)
        val earned = allowance(monthStart, today).suggestedAllowance
        return AdminFundFactory.deriveAll(RewardFundState(cap, potOpened, earned))
    }

    /**
     * Everything the per-quest status paths need for one [epochDay], assembled once.
     * Callers (plan, dismissal, progress, overview) read from this instead of each
     * re-reading the profile, re-deriving candidates, and issuing a [CompletionDao.find]
     * per quest — the pattern that made a Quests/Today refresh scale as several full
     * recomputes plus ~2N point queries with the quest count.
     */
    private data class DayContext(
        val epochDay: Long,
        val profile: UserProfile,
        /** Active stored quests as models (Quests-screen backlog + plan input). */
        val stored: List<Quest>,
        /** Quests derived from habits/bad-habits/goals. */
        val derivedHabits: List<Quest>,
        /**
         * Every quest that can appear in a plan, keyed by id: [stored], [derivedHabits],
         * and the system routine + reward-fund admin quests. Used wherever a quest's
         * style is needed, so non-stored quests aren't silently treated as binary or
         * skipped for progress/dismissal — a completed morning routine (or a skipped
         * admin step) must leave today's plan like anything else.
         */
        val candidates: Map<String, Quest>,
        val lastCompleted: Map<String, Long>,
        /**
         * Each candidate's *current-interval* completion record (the day for
         * daily/binary quests, the week/month for measured recurring ones), keyed by
         * quest id. Absent → no record this interval. Fetched in one IN-clause query.
         */
        val intervalRecords: Map<String, CompletionRecord>,
    )

    private suspend fun buildDayContext(epochDay: Long): DayContext {
        val profile = profileStore.profile.first()
        val stored = questDao.getActive().map { it.toModel() }
        val derivedHabits = HabitQuestFactory.deriveAll(profile.habits, profile.badHabits, profile.goals)
        val system = RoutineQuestFactory.all() + AdminFundFactory.all()
        val candidates = (stored + derivedHabits + system).associateBy { it.id }
        val lastCompleted = completionDao.lastCompletedDays().associate { it.questId to it.lastDay }
        // Map each candidate to its current-interval instanceId, then fetch them all
        // in a single point-query instead of one find() per quest.
        val firstDay = profile.preferences.firstDayOfWeek
        val instanceIdByQuest = candidates.values.associate { quest ->
            quest.id to "${quest.id}@${CompletionSlots.completionSlot(quest, epochDay, firstDay)}"
        }
        // Chunk the IN-list: Room binds one variable per id, and a large dataset (many
        // quests/habits/goals, or an imported backup) can exceed SQLite's ~999-variable
        // limit on older devices (minSdk 26) — which would fail the whole refresh, a
        // regression the per-quest find() didn't have. Chunks stay well under the floor.
        val recordsById = instanceIdByQuest.values
            .chunked(SQLITE_MAX_IN_VARIABLES)
            .flatMap { completionDao.findByInstanceIds(it) }
            .associate { it.instanceId to it.toModel() }
        val intervalRecords = instanceIdByQuest.mapNotNull { (questId, instanceId) ->
            recordsById[instanceId]?.let { questId to it }
        }.toMap()
        return DayContext(epochDay, profile, stored, derivedHabits, candidates, lastCompleted, intervalRecords)
    }

    /** The user's configured first day of the week (default Sunday), for interval math. */
    private suspend fun firstDayOfWeek(): DayOfWeek =
        profileStore.profile.first().preferences.firstDayOfWeek

    /** A measured quest's accumulated progress this interval (count or minutes). */
    private suspend fun intervalProgress(quest: Quest, epochDay: Long, firstDayOfWeek: DayOfWeek): Int {
        val target = when (quest.completionStyle) {
            CompletionStyle.QUANTITATIVE -> quest.targetCount ?: return 0
            CompletionStyle.DURATION -> quest.estimatedMinutes
            else -> return 0
        }
        val slot = CompletionSlots.completionSlot(quest, epochDay, firstDayOfWeek)
        val rec = completionDao.find("${quest.id}@$slot") ?: return 0
        return (rec.fraction * target).roundToInt()
    }

    /**
     * Quest ids that should not appear again today. Style-aware: a partially
     * logged QUANTITATIVE/DURATION quest is *not* dismissed so the user can keep
     * adding progress. Reads the current-interval records already in [ctx] — a
     * weekly quest finished earlier this week stays hidden the rest of the interval.
     */
    private fun dismissedQuestIds(ctx: DayContext): Set<String> =
        ctx.candidates.values.mapNotNullTo(mutableSetOf()) { quest ->
            val result = ctx.intervalRecords[quest.id]?.result ?: return@mapNotNullTo null
            quest.id.takeIf {
                CompletionPolicy.dismissedForToday(quest.completionStyle, result, quest.allowOverCompletion)
            }
        }

    /**
     * Accumulated progress per measured quest for its *current interval* (count for
     * QUANTITATIVE, minutes for DURATION), so the UI resumes from where it left off
     * — for a weekly quest that's the whole week's progress, not just today's.
     */
    suspend fun todayProgress(epochDay: Long): Map<String, Int> = progressByQuest(buildDayContext(epochDay))

    private fun progressByQuest(ctx: DayContext): Map<String, Int> = buildMap {
        for (quest in ctx.candidates.values) {
            val target = when (quest.completionStyle) {
                CompletionStyle.QUANTITATIVE -> quest.targetCount ?: continue
                CompletionStyle.DURATION -> quest.estimatedMinutes
                else -> continue
            }
            // An entry exists iff this quest has a record this interval (even a
            // zero-progress log), so the UI can resume/show 0 as before.
            val rec = ctx.intervalRecords[quest.id] ?: continue
            put(quest.id, (rec.fraction * target).roundToInt())
        }
    }

    data class CompleteOutcome(
        val effect: QuestLoopEngine.CompletionEffect,
        val newlyUnlocked: List<Achievement>,
        /** Identifiers to reverse this action (snackbar "Undo"). */
        val instanceId: String,
        val previousRecord: CompletionRecord?,
    )

    // --- Completed-quest history (Completed screen) --------------------------

    /**
     * One completed entry for the history screen: the record, a display title, the
     * backing quest (null if we can't even name it), and whether it can be edited /
     * re-added. Only *stored* quests (incl. archived) are [editable]: a derived
     * habit/goal quest, a system routine, or an admin-fund step is named for display
     * but can't be persisted through the editor, and a re-add would mint a stray
     * duplicate — so those actions are disabled for them.
     */
    data class CompletedEntry(
        val record: CompletionRecord,
        val title: String,
        val quest: Quest?,
        val editable: Boolean = false,
    )

    /**
     * Completed quests for the history screen, newest first. [range] bounds the
     * epoch-day window (Today/Week/Month); null is all-time. Fully-completed
     * records are shown (partials are in-progress, not history), plus skipped ones
     * — so a skip can still be reversed after the snackbar's Undo is gone (the
     * row's Undo removes the record and the gentle penalty re-derives away). The
     * filter, sort, and row→entry mapping run on [historyDispatcher], so the
     * all-time slice never transforms the whole ledger on Main. Titles are resolved
     * from stored (incl. archived) + derived + routine + admin quests so every row
     * is named; only stored quests are marked [CompletedEntry.editable].
     */
    suspend fun completedHistory(range: LongRange? = null): List<CompletedEntry> =
        withContext(historyDispatcher) {
            val rows = if (range == null) completionDao.all() else completionDao.between(range.first, range.last)
            val profile = profileStore.profile.first()
            val stored = questDao.getAll().map { it.toModel() }
            val storedIds = stored.map { it.id }.toSet()
            val derived = HabitQuestFactory.deriveAll(profile.habits, profile.badHabits, profile.goals)
            val routines = RoutineQuestFactory.all()
            val admin = listOf(
                AdminFundFactory.openPotQuest(), AdminFundFactory.fundMonthQuest(), AdminFundFactory.claimQuest(),
            )
            val quests = (stored + derived + routines + admin).associateBy { it.id }
            rows.map { it.toModel() }
                .filter { it.result == CompletionResult.COMPLETED || it.result == CompletionResult.SKIPPED }
                .sortedByDescending { it.epochDay }
                .map { rec ->
                    val quest = quests[rec.questId]
                    val title = quest?.title ?: rec.category.name.lowercase().replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                    CompletedEntry(record = rec, title = title, quest = quest, editable = rec.questId in storedIds)
                }
        }

    /** Undo a completion from the history screen: remove it entirely; XP re-derives. */
    suspend fun deleteCompletion(instanceId: String) = completionMutex.withLock {
        completionDao.delete(instanceId)
    }

    /**
     * Edit (full quest editor) from the history screen: persist the updated quest
     * definition and re-score the clicked completion with it, so a difficulty/
     * priority change updates that record's XP too. Returns null if the completion
     * is gone. Only stored quests are editable (derived/routine ones aren't).
     */
    suspend fun editQuestAndRescore(updatedQuest: Quest, instanceId: String): CompleteOutcome? =
        completionMutex.withLock {
            val existing = completionDao.find(instanceId)?.toModel() ?: return@withLock null
            questDao.getAll().firstOrNull { it.id == updatedQuest.id }?.let { stored ->
                questDao.upsert(updatedQuest.toEntity(archived = stored.archived))
            }
            // If the edit re-keys the record (a frequency change that moves the
            // interval slot, e.g. daily → weekly), delete the stale original BEFORE
            // re-scoring. That way the ledger baseline nets out its old grant, it isn't
            // seen as a same-day sibling (a spurious anti-farm decay), no orphan is
            // left behind, and the returned outcome's reported total/level are honest.
            // A same-slot edit keeps the same instanceId, so completeQuestLocked nets
            // the prior grant itself (nothing to delete).
            val slot = CompletionSlots.completionSlot(updatedQuest, existing.epochDay, firstDayOfWeek())
            val newInstanceId = "${updatedQuest.id}@$slot"
            if (newInstanceId != instanceId) completionDao.delete(instanceId)
            completeQuestLocked(
                updatedQuest, existing.epochDay, existing.result, existing.fraction, existing.verification,
            )
        }

    /** Re-add: clone a completed quest's definition as a fresh active quest. */
    suspend fun readdQuest(source: Quest): Quest {
        val copy = source.copy(id = "user-${java.util.UUID.randomUUID()}")
        addQuest(copy)
        return copy
    }

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
     * (`questId@slot`, where the slot is the day for most quests, the calendar
     * interval start for a measured weekly/monthly quest, and a fixed lifetime
     * slot for a measured one-off); re-logging the same instance replaces its
     * prior grant via the ledger, so XP never double-counts (the prior
     * `xpAwarded` is netted out before the new grant is applied).
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
        // Measured recurring quests are *keyed* by their interval start (so weekly/
        // monthly progress accumulates into one record and resets at the boundary),
        // and a measured one-off by its single lifetime slot (accumulates until the
        // target is reached once, never resets); everything else is keyed by the
        // day, unchanged. The record's `epochDay`,
        // however, is always the REAL log day — it drives streak / active-days /
        // same-day anti-farm / history, none of which must be shifted to the interval
        // start (that would break the streak and mis-date the completed-history row).
        //
        // Trade-off: a measured interval quest is one logical unit stored as ONE row,
        // so it contributes ONE active day per interval (a one-off's interval is its
        // whole lifetime) — the most recent log (which
        // keeps *today's* streak alive). If it's logged on several days of the same
        // interval, only the latest is recorded; the earlier days aren't independently
        // marked active by this quest (other quests/routines on those days still are).
        // This is the cost of clean interval accumulation + over-completion scoring
        // (which needs a single record carrying the cumulative fraction).
        val firstDay = firstDayOfWeek()
        val slot = CompletionSlots.completionSlot(quest, epochDay, firstDay)
        val instanceId = "${quest.id}@$slot"
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
        // Score the streak bonus with the user's configured grace days, so the
        // streak driving the reward matches the one shown in the UI (which also
        // reads preferences.streakGraceDays); otherwise the engine's default of 1
        // would silently diverge from a user who set a longer grace window.
        val grace = profileStore.profile.first().preferences.streakGraceDays
        val context = engine.contextFrom(record, sameDay, activeDays, grace)

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
        val existingProgress = intervalProgress(quest, epochDay, firstDayOfWeek())
        val allowOver = quest.allowOverCompletion
        val scaled = when (quest.completionStyle) {
            CompletionStyle.QUANTITATIVE ->
                CompletionScaling.quantitative(maxOf(existingProgress, value), (quest.targetCount ?: 1).coerceAtLeast(1), allowOver)
            CompletionStyle.DURATION ->
                CompletionScaling.duration(maxOf(existingProgress, value), quest.estimatedMinutes.coerceAtLeast(1), allowOver)
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

    /**
     * Forward-looking weekly/monthly plan over the inclusive `[fromEpochDay,
     * toEpochDay]` window — the same candidate pool as [todayPlan] (stored quests
     * + derived habit/goal quests), but laid out across the whole period instead
     * of a single day. Powers the Reviews tab's "Plan" view.
     */
    suspend fun periodPlan(
        periodLabel: String,
        fromEpochDay: Long,
        toEpochDay: Long,
    ): PeriodPlanner.PeriodPlan {
        val profile = profileStore.profile.first()
        val lastCompleted = completionDao.lastCompletedDays().associate { it.questId to it.lastDay }
        val derived = HabitQuestFactory.deriveAll(profile.habits, profile.badHabits, profile.goals) +
            adminFundQuests(fromEpochDay, profile, lastCompleted)
        val candidates = questDao.getActive().map { it.toModel() } + derived
        return periodPlanner.plan(
            periodLabel, fromEpochDay, toEpochDay, candidates, lastCompleted,
            profile.preferences.firstDayOfWeek,
        )
    }

    suspend fun allowance(fromEpochDay: Long, toEpochDay: Long): RewardAllowanceCalculator.AllowanceResult {
        val profile = profileStore.profile.first()
        // Exclude meta-maintenance/admin records so the reward-fund bookkeeping
        // (and routines) never counts as real-world earning (SPEC §6–7); the
        // calculator drops them too, but activeDays is computed here.
        val records = completionDao.between(fromEpochDay, toEpochDay).map { it.toModel() }
            .filterNot { it.isMeta }
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

    /** Live status of the reward-fund admin flow, for the Rewards screen. */
    data class RewardFundStatus(
        val budgetSet: Boolean,
        val potOpened: Boolean,
        /** Admin steps actionable right now (open pot / fund this month / claim). */
        val steps: List<Quest>,
    )

    suspend fun rewardFundStatus(today: Long): RewardFundStatus {
        val profile = profileStore.profile.first()
        val lastCompleted = completionDao.lastCompletedDays().associate { it.questId to it.lastDay }
        val cap = profile.preferences.monthlyRewardBudgetCap
        val potOpened = lastCompleted.containsKey(AdminFundFactory.OPEN_POT_ID)
        val steps = adminFundQuests(today, profile, lastCompleted)
            .filter { QuestScheduler.isDue(it.frequency, today, lastCompleted[it.id]) }
        return RewardFundStatus(budgetSet = cap > 0.0, potOpened = potOpened, steps = steps)
    }

    /** Upcoming calendar events for the Add-quest deadline picker (SPEC §10). */
    suspend fun upcomingCalendarEvents(daysAhead: Int = 14): List<CalendarEventSummary> =
        calendarReader.upcomingEvents(daysAhead)

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

    suspend fun setCalendarBudgetEnabled(value: Boolean) = profileStore.setCalendarBudgetEnabled(value)

    suspend fun setFirstDayOfWeek(day: DayOfWeek) = profileStore.setFirstDayOfWeek(day)

    suspend fun addHabit(habit: Habit) = profileMutex.withLock {
        val current = profileStore.profile.first().habits
        profileStore.setHabits(current.filterNot { it.id == habit.id } + habit)
    }

    suspend fun removeHabit(id: String) = profileMutex.withLock {
        profileStore.setHabits(profileStore.profile.first().habits.filterNot { it.id == id })
    }

    suspend fun addBadHabit(badHabit: BadHabit) = profileMutex.withLock {
        val current = profileStore.profile.first().badHabits
        profileStore.setBadHabits(current.filterNot { it.id == badHabit.id } + badHabit)
    }

    suspend fun removeBadHabit(id: String) = profileMutex.withLock {
        profileStore.setBadHabits(profileStore.profile.first().badHabits.filterNot { it.id == id })
    }

    suspend fun addGoal(goal: com.questloop.core.model.Goal) = profileMutex.withLock {
        val current = profileStore.profile.first().goals
        profileStore.setGoals(current.filterNot { it.id == goal.id } + goal)
    }

    suspend fun removeGoal(id: String) = profileMutex.withLock {
        profileStore.setGoals(profileStore.profile.first().goals.filterNot { it.id == id })
    }

    /** Serialises all on-device data to JSON for export (SPEC §9 portability). */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        // Include archived quests so export is a complete backup, not just active data.
        val all = questDao.getAll().map { it.toModel() to it.archived }
        val snapshot = ExportSnapshot(
            quests = all.map { it.first },
            completions = completionDao.all().map { it.toModel() },
            profile = profileStore.profile.first(),
            archivedIds = all.filter { it.second }.map { it.first.id },
        )
        // Encoding the full ledger can be heavy; keep it off the main thread
        // (matches importJson, which already does this).
        exportJson.encodeToString(ExportSnapshot.serializer(), snapshot)
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

        // Everything past validation is wrapped so a mid-import store failure
        // (a DataStore/Room write throwing) surfaces as a clean error result
        // rather than an uncaught exception that crashes the app.
        runCatching {
            // Merge profile first so derived (habit/goal) quest ids are known. Hold
            // profileMutex for the read-modify-write so a concurrent habit/goal edit
            // can't clobber (or be clobbered by) the import on a stale snapshot. This
            // section is sequential with — never nested inside — the completionMutex
            // block below, so there's no lock-ordering hazard.
            val (habits, badHabits, goals) = profileMutex.withLock {
                val current = profileStore.profile.first()
                val prefs = snapshot.profile.preferences
                val h = mergeById(current.habits, snapshot.profile.habits) { it.id }
                val b = mergeById(current.badHabits, snapshot.profile.badHabits) { it.id }
                val g = mergeById(current.goals, snapshot.profile.goals) { it.id }
                profileStore.setHabits(h)
                profileStore.setBadHabits(b)
                profileStore.setGoals(g)
                profileStore.setMaxDaily(prefs.maxDailyQuests)
                profileStore.setAvailableMinutes(prefs.defaultAvailableMinutes)
                profileStore.setBudgetCap(prefs.monthlyRewardBudgetCap)
                profileStore.setFocusCategories(prefs.focusCategories)
                profileStore.setStreakGraceDays(prefs.streakGraceDays)
                profileStore.setSensitiveOptIn(prefs.sensitiveNotificationsOptIn)
                Triple(h, b, g)
            }

            // The set of quest ids a completion may legitimately reference.
            val derivedIds = HabitQuestFactory.deriveAll(habits, badHabits, goals).map { it.id }
            val routineIds = RoutineQuestFactory.all().map { it.id }
            val existingIds = questDao.getAll().map { it.id }
            val validQuestIds = (
                snapshot.quests.map { it.id } + existingIds + derivedIds + routineIds + AdminFundFactory.ALL_IDS
            ).toSet()

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
        }.getOrElse {
            ImportResult(0, 0, error = "Couldn't finish importing that backup. Your existing data is unchanged where possible.")
        }
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

    /** Clears today's energy check-in (deselecting the chip reverts to the default plan). */
    suspend fun clearCheckIn() = profileStore.setCheckIn(null)

    suspend fun aiConfig(): AiConfig = profileStore.getAiConfig()
    suspend fun setAiConfig(config: AiConfig) = profileStore.setAiConfig(config)

    /**
     * Runs the "Sign in with ChatGPT" OAuth flow and, on success, links the OpenAI
     * account: stores the tokens, selects the OpenAI provider, and turns AI on.
     * [openUrl] is invoked with the browser URL the user must open. Returns the
     * failure reason (for the UI) when the handshake doesn't complete.
     */
    suspend fun connectOpenAi(openUrl: (String) -> Unit): Result<Unit> {
        // Snapshot the clear generation before the (long, user-driven) handshake so
        // onTokens can tell whether a sign-out/delete-all landed while it ran.
        val genAtStart = aiAuthMutex.withLock { credentialClearGeneration }
        return openAiAuth.signIn(
            // Runs inside the sign-in's non-cancellable completion: once the browser
            // says "You're signed in", the link is persisted even if this coroutine
            // was cancelled (screen closed) mid-handshake. Persisting can throw if
            // the encrypted key store rejects the write; signIn surfaces that as a
            // failure (so the caller shows a message + clears its busy state)
            // rather than letting it propagate past the caller's cleanup.
            onTokens = { tokens ->
                // Persist under the same mutex as refresh/sign-out/wipe, and abort if
                // credentials were cleared during the handshake: a stale browser
                // callback must not resurrect tokens (and re-enable AI) after an
                // explicit "delete all my data" or sign-out.
                aiAuthMutex.withLock {
                    if (credentialClearGeneration != genAtStart) {
                        throw java.io.IOException("Sign-in was cancelled. Please sign in again in Settings.")
                    }
                    val config = profileStore.getAiConfig()
                    profileStore.setAiConfig(config.copy(enabled = true, provider = AiProvider.OPENAI, openAiTokens = tokens))
                }
            },
            openUrl = openUrl,
        ).map { }
    }

    /**
     * Unlinks the OpenAI account (drops the stored tokens); leaves OpenRouter config
     * intact. Serialised with [freshOpenAiTokens] so an in-flight token refresh
     * can't re-persist the tokens after the sign-out.
     */
    suspend fun disconnectOpenAi() = aiAuthMutex.withLock {
        credentialClearGeneration++
        val config = profileStore.getAiConfig()
        profileStore.setAiConfig(config.copy(openAiTokens = null))
    }

    /** Builds the [LlmClient] for the configured provider (OpenAI refreshes tokens lazily). */
    private fun llmClient(config: AiConfig): com.questloop.core.ai.LlmClient = when (config.provider) {
        AiProvider.OPENROUTER -> OpenRouterClient(config.apiKey, config.activeModel)
        AiProvider.OPENAI -> OpenAiClient(config.activeModel, openAiResponsesEndpoint) { force -> freshOpenAiTokens(force) }
    }

    /**
     * Returns a usable OpenAI token bundle, refreshing + persisting it when expired
     * (or when [forceRefresh] is set after a 401). Serialised (with [disconnectOpenAi]
     * and [deleteAllData]) so concurrent AI calls don't both spend the rotating
     * refresh token. Throws when not signed in or the refresh fails, so the error
     * flows through [AiQuestService]'s per-call handling.
     */
    private suspend fun freshOpenAiTokens(forceRefresh: Boolean) = aiAuthMutex.withLock {
        val config = profileStore.getAiConfig()
        val tokens = config.openAiTokens
            ?: throw java.io.IOException("You're not signed in to ChatGPT. Sign in again in Settings.")
        val now = System.currentTimeMillis() / 1000
        if (!forceRefresh && !tokens.isExpired(now)) return@withLock tokens
        val refreshed = openAiAuth.refresh(tokens).getOrElse {
            throw java.io.IOException("Your ChatGPT sign-in expired. Please sign in again in Settings.")
        }
        // Re-read before persisting: a writer that bypasses this mutex (e.g. a
        // Settings save) may have changed the config during the network refresh.
        // If the tokens were cleared meanwhile, honor the sign-out — abort rather
        // than resurrect credentials; otherwise persist onto the fresh snapshot so
        // only the token slot changes.
        val current = profileStore.getAiConfig()
        if (current.openAiTokens == null) {
            throw java.io.IOException("You're not signed in to ChatGPT. Sign in again in Settings.")
        }
        profileStore.setAiConfig(current.copy(openAiTokens = refreshed))
        refreshed
    }

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
                AiQuestService(llmClient(config)).suggest(input)
            }
            // Record real failures so the user can export them for troubleshooting.
            suggestion.error?.let { recordAiError(config, it, suggestion.errorDetail) }
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
                AiQuestService(llmClient(config)).decomposeGoal(goal, existing)
            }
            result.error?.let { recordAiError(config, it, result.errorDetail) }
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
            AiQuestService(llmClient(config)).refine(quest, instruction)
        }
        result.error?.let { recordAiError(config, it, result.errorDetail) }
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
            AiNarrator(llmClient(config))
                .narrateReview(review, sanitize = config.filterWording)
        }
        // Record any AI miss (transport, style reject, or empty) so misbehaviour is
        // visible in the exportable log — not just transport failures.
        if (!result.fromAi && result.note != null) recordAiError(config, "review narration: ${result.note}")
        return result
    }

    /**
     * AI errors are logged off the main thread (the diagnostics file is read+rewritten).
     * [detail] is the raw transport failure behind a plain-copy [message] — appended
     * here so the exportable log keeps it while the UI never shows it.
     */
    private suspend fun recordAiError(config: AiConfig, message: String, detail: String? = null) = withContext(Dispatchers.IO) {
        val logged = if (detail.isNullOrBlank()) message else "$message ($detail)"
        // The OpenAI call can rotate + persist tokens mid-flight (freshOpenAiTokens),
        // so the [config] snapshot taken before the call may no longer hold the
        // credentials that were actually sent. Scrub with the snapshot AND a fresh
        // read — best-effort, since logging an AI error must never itself throw.
        var scrubbed = redactSecrets(logged, config)
        runCatching { profileStore.getAiConfig() }.getOrNull()
            ?.let { current -> scrubbed = redactSecrets(scrubbed, current) }
        aiDiagnostics.record(config.activeModel, scrubbed)
    }

    /**
     * Erases all on-device data (SPEC §9: users can delete their data). Serialised
     * with [freshOpenAiTokens] so an in-flight token refresh can't re-persist the
     * AI credentials after the wipe.
     */
    suspend fun deleteAllData() = aiAuthMutex.withLock {
        credentialClearGeneration++
        completionDao.clear()
        questDao.clear()
        profileStore.clear()
        // The exportable AI error log is on-device data too — a full wipe removes it.
        clearAiDiagnostics()
    }
}

/**
 * Max ids per `WHERE instanceId IN (...)` batch. SQLite's default
 * SQLITE_MAX_VARIABLE_NUMBER is 999 on the older devices we support (minSdk 26);
 * 900 leaves headroom for any other bound params and keeps large candidate sets
 * from overflowing the limit and failing the whole lookup.
 */
private const val SQLITE_MAX_IN_VARIABLES = 900

/**
 * Defense-in-depth for the user-shareable AI diagnostics log: scrub the API key
 * out of any text before it's recorded. [OpenRouterClient] builds its error from
 * the response *body* (never the `Authorization` header), so the key shouldn't
 * appear today — but if an upstream message ever changes, this guarantees the key
 * can't leak into a log the user exports. No-op when [apiKey] is blank.
 */
internal fun redactApiKey(message: String, apiKey: String): String =
    if (apiKey.isNotBlank()) message.replace(apiKey, "***") else message

/**
 * Scrubs every provider secret from a diagnostics message: the OpenRouter API key
 * and, for the OpenAI provider, the OAuth access + refresh tokens. Same rationale
 * as [redactApiKey] — the tokens shouldn't appear in error bodies, but this makes
 * sure they can never leak into an exported log. Also strips anything shaped like
 * an `Authorization: Bearer <token>` value, a catch-all for credentials [config]
 * no longer holds (e.g. an access token that rotated mid-call).
 */
internal fun redactSecrets(message: String, config: AiConfig): String {
    var out = redactApiKey(message, config.apiKey)
    config.openAiTokens?.let { tokens ->
        out = redactApiKey(out, tokens.accessToken)
        out = redactApiKey(out, tokens.refreshToken)
    }
    return out.replace(BEARER_SHAPED_TOKEN, "Bearer ***")
}

/**
 * `Bearer` followed by a long token-charset run (RFC 6750 shape). The length floor
 * keeps prose such as "invalid Bearer token" readable — real credentials (JWTs,
 * API keys) are far longer than 20 characters.
 */
private val BEARER_SHAPED_TOKEN = Regex("""Bearer\s+[A-Za-z0-9\-._~+/=]{20,}""", RegexOption.IGNORE_CASE)
