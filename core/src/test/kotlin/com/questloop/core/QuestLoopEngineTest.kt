package com.questloop.core

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestLoopEngineTest {

    private val engine = QuestLoopEngine()

    private fun rec(
        questId: String,
        day: Long,
        result: CompletionResult = CompletionResult.COMPLETED,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        difficulty: Difficulty = Difficulty.MEDIUM,
    ) = CompletionRecord(
        instanceId = "$questId@$day",
        questId = questId,
        category = category,
        difficulty = difficulty,
        priority = Priority.NORMAL,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        isMeta = category.isMeta,
    )

    @Test
    fun `recording a completion adds xp`() {
        val effect = engine.recordCompletion(0, rec("q1", 100), emptyList())
        assertEquals(20L, effect.outcome.xp)
        assertEquals(20L, effect.newTotalXp)
    }

    @Test
    fun `level up is detected`() {
        // Need 100 xp to reach level 2.
        val effect = engine.recordCompletion(
            previousTotalXp = 90,
            newRecord = rec("q1", 100, difficulty = Difficulty.MEDIUM),
            history = emptyList(),
        )
        assertEquals(1, effect.previousLevel)
        assertTrue(effect.leveledUp)
        assertEquals(2, effect.newLevel)
    }

    @Test
    fun `xp never goes negative from penalties`() {
        val effect = engine.recordCompletion(
            previousTotalXp = 0,
            newRecord = rec("q1", 100, result = CompletionResult.FAILED),
            history = emptyList(),
        )
        assertEquals(0L, effect.newTotalXp)
    }

    @Test
    fun `re-logging the same instance is not penalised`() {
        // Re-logging the same quest instance (e.g. updating progress) replaces
        // the prior record, so it must not trigger anti-farm decay.
        val prior = rec("q1", 100, difficulty = Difficulty.EASY).copy(xpAwarded = 10)
        val relog = engine.recordCompletion(10, rec("q1", 100, difficulty = Difficulty.EASY), listOf(prior))
        val fresh = engine.recordCompletion(0, rec("q2", 100, difficulty = Difficulty.EASY), emptyList())
        assertEquals(fresh.outcome.xp, relog.outcome.xp)
    }

    @Test
    fun `derives streak from history for consistency bonus`() {
        val history = (90L..99L).map { rec("daily", it) }
        val effect = engine.recordCompletion(1000, rec("daily", 100), history)
        val noHistory = engine.recordCompletion(1000, rec("other", 100), emptyList())
        assertTrue(effect.outcome.xp >= noHistory.outcome.xp, "streak should not reduce reward")
    }

    @Test
    fun `honesty xp is capped per day`() {
        // Logging many bad-habit relapses honestly is rewarded, but bounded so it
        // can't be farmed into unlimited XP (H1).
        var total = 0L
        val history = mutableListOf<CompletionRecord>()
        repeat(5) { i ->
            val r = rec(
                "bad$i",
                100,
                result = CompletionResult.FAILED,
                category = QuestCategory.BAD_HABIT_REDUCTION,
                difficulty = Difficulty.EASY,
            )
            val effect = engine.recordCompletion(total, r, history)
            total = effect.newTotalXp
            history += r.copy(xpAwarded = effect.outcome.xp)
        }
        assertEquals(9L, total, "honesty XP should be capped at 9/day")
    }

    @Test
    fun `many distinct easy quests are capped per day`() {
        // Same-quest farming is handled by anti-farm decay; this covers the
        // many-distinct-trivial-quests lane (H2). Each EASY quest is 10 XP.
        var total = 0L
        val history = mutableListOf<CompletionRecord>()
        var capHit = false
        repeat(12) { i ->
            val r = rec("easy$i", 100, difficulty = Difficulty.EASY)
            val effect = engine.recordCompletion(total, r, history)
            total = effect.newTotalXp
            history += r.copy(xpAwarded = effect.outcome.xp)
            if (effect.outcome.capReason != null) capHit = true
        }
        assertEquals(40L, total, "low-effort XP should be capped at 40/day")
        assertTrue(capHit, "cap reason should have been surfaced")
    }

    @Test
    fun `harder quests are unaffected by the low-effort cap`() {
        // A productive day of real (MEDIUM+) work is never capped by the
        // easy-quest ceiling.
        var total = 0L
        val history = mutableListOf<CompletionRecord>()
        repeat(6) { i ->
            val r = rec("real$i", 100, difficulty = Difficulty.MEDIUM)
            val effect = engine.recordCompletion(total, r, history)
            total = effect.newTotalXp
            history += r.copy(xpAwarded = effect.outcome.xp)
        }
        assertEquals(120L, total, "6 medium quests at 20 XP each, uncapped")
    }

    @Test
    fun `zero-progress partial does not sustain a streak`() {
        // Active on 98 & 99, then only an empty (0-fraction) partial on day 100.
        // That empty partial must not count as activity (M1), so with grace=1 the
        // gap on day 100 ends the run rather than masking it.
        val history = listOf(
            rec("q", 98),
            rec("q", 99),
            rec("q", 100, result = CompletionResult.PARTIAL),
        )
        val ctx = engine.deriveContext(rec("q", 101), history)
        assertEquals(0, ctx.currentStreakDays, "an empty partial can't prop up a streak")

        // A genuine partial (fraction > 0) on day 100 keeps the run alive.
        val realProgress = history.dropLast(1) +
            rec("q", 100, result = CompletionResult.PARTIAL).copy(fraction = 0.5)
        val ctx2 = engine.deriveContext(rec("q", 101), realProgress)
        assertTrue(ctx2.currentStreakDays >= 3, "real progress sustains the streak")
    }

    @Test
    fun `meta cap enforced via derived context`() {
        // Three meta completions same day; total should be capped at 30.
        var total = 0L
        val history = mutableListOf<CompletionRecord>()
        var capHit = false
        repeat(5) { i ->
            val r = rec("meta$i", 100, category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.MEDIUM)
            val effect = engine.recordCompletion(total, r, history)
            total = effect.newTotalXp
            // Store the granted XP, as the repository does, so per-day caps see
            // the real amounts.
            history += r.copy(xpAwarded = effect.outcome.xp)
            if (effect.outcome.capReason != null) capHit = true
        }
        assertTrue(total <= 30, "meta total should be capped at 30, was $total")
        assertTrue(capHit, "cap reason should have been surfaced")
    }

    @Test
    fun `contextFrom honours an explicit graceDays for the streak`() {
        // Active on 97 and 98, then a gap (99 and today 100 not yet active).
        val record = rec("q1", 100)
        val activeDays = setOf(97L, 98L)

        // grace 0: the gap at 99/100 breaks the streak immediately.
        val strict = engine.contextFrom(record, emptyList(), activeDays, graceDays = 0)
        assertEquals(0, strict.currentStreakDays)

        // grace 2: the two missed days are tolerated, so 97+98 still count.
        val lenient = engine.contextFrom(record, emptyList(), activeDays, graceDays = 2)
        assertEquals(2, lenient.currentStreakDays)
    }
}
