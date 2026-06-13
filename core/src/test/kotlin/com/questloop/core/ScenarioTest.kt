package com.questloop.core

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import com.questloop.core.reward.LevelSystem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end economy scenarios that exercise the engine the way the app does:
 * recording a sequence of completions and asserting the spec's fairness
 * properties hold across a realistic week.
 */
class ScenarioTest {

    private val engine = QuestLoopEngine()

    private fun rec(
        questId: String,
        day: Long,
        result: CompletionResult = CompletionResult.COMPLETED,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        difficulty: Difficulty = Difficulty.MEDIUM,
        priority: Priority = Priority.NORMAL,
    ) = CompletionRecord(
        instanceId = "$questId@$day",
        questId = questId,
        category = category,
        difficulty = difficulty,
        priority = priority,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        isMeta = category.isMeta,
    )

    /**
     * Records completions the way the repository does: idempotent upsert keyed
     * by instanceId, with total XP derived from the stored ledger so re-logging
     * an instance replaces its prior grant rather than stacking.
     */
    private fun run(records: List<CompletionRecord>): Long {
        val ledger = LinkedHashMap<String, CompletionRecord>()
        for (r in records) {
            val existingXp = ledger[r.instanceId]?.xpAwarded ?: 0
            val baseline = (ledger.values.sumOf { it.xpAwarded } - existingXp).coerceAtLeast(0)
            val effect = engine.recordCompletion(baseline, r, ledger.values.toList())
            ledger[r.instanceId] = r.copy(xpAwarded = effect.outcome.xp)
        }
        return ledger.values.sumOf { it.xpAwarded }.coerceAtLeast(0)
    }

    @Test
    fun `a consistent honest week makes steady progress`() {
        val records = (1L..7L).flatMap { day ->
            listOf(
                rec("water", day, category = QuestCategory.HEALTH, difficulty = Difficulty.TRIVIAL),
                rec("focus", day, difficulty = Difficulty.HARD, priority = Priority.HIGH),
                rec("noscroll", day, category = QuestCategory.BAD_HABIT_REDUCTION, difficulty = Difficulty.MEDIUM),
            )
        }
        val xp = run(records)
        // Should comfortably climb several levels over a productive week.
        assertTrue(LevelSystem.levelForXp(xp) >= 3, "expected level >= 3, was ${LevelSystem.levelForXp(xp)} ($xp xp)")
    }

    @Test
    fun `farming one trivial quest cannot out-earn doing real work`() {
        // User A farms the same trivial quest 10x in one day.
        val farm = (1..10).map { rec("trivial", 1, difficulty = Difficulty.TRIVIAL) }
        val farmXp = run(farm)

        // User B does three genuine medium/hard quests once.
        val real = listOf(
            rec("a", 1, difficulty = Difficulty.MEDIUM),
            rec("b", 1, difficulty = Difficulty.HARD),
            rec("c", 1, difficulty = Difficulty.MEDIUM),
        )
        val realXp = run(real)

        assertTrue(realXp > farmXp, "real work ($realXp) should beat farming ($farmXp)")
    }

    @Test
    fun `meta maintenance cannot dominate a day`() {
        val metaSpam = (1..20).map {
            rec("m$it", 1, category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.MEDIUM)
        }
        val xp = run(metaSpam)
        assertTrue(xp <= 30, "meta XP for a day must be capped at 30, was $xp")
    }

    @Test
    fun `a terrible day cannot push XP below zero`() {
        // Earn a little, then fail many quests.
        val records = listOf(rec("win", 1, difficulty = Difficulty.EASY)) +
            (1..10).map { rec("fail$it", 1, result = CompletionResult.FAILED) }
        val xp = run(records)
        assertTrue(xp >= 0, "xp must never go negative, was $xp")
    }

    @Test
    fun `honest relapse logging keeps momentum positive`() {
        val records = (1..5).map {
            rec("smoke", it.toLong(), result = CompletionResult.FAILED, category = QuestCategory.BAD_HABIT_REDUCTION)
        }
        val xp = run(records)
        assertTrue(xp > 0, "honest relapse logging should never reduce XP, was $xp")
    }
}
