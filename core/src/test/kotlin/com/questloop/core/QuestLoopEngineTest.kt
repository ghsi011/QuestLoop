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
}
