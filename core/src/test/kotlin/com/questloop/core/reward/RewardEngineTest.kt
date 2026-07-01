package com.questloop.core.reward

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RewardEngineTest {

    private val engine = RewardEngine()

    private fun record(
        result: CompletionResult = CompletionResult.COMPLETED,
        difficulty: Difficulty = Difficulty.MEDIUM,
        priority: Priority = Priority.NORMAL,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        questId: String = "q1",
        fraction: Double = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        epochDay: Long = 100,
    ) = CompletionRecord(
        instanceId = "$questId@$epochDay",
        questId = questId,
        category = category,
        difficulty = difficulty,
        priority = priority,
        result = result,
        epochDay = epochDay,
        fraction = fraction,
        isMeta = category.isMeta,
    )

    @Test
    fun `base completion grants difficulty base xp`() {
        val out = engine.score(record(difficulty = Difficulty.MEDIUM))
        assertEquals(20L, out.xp)
    }

    @Test
    fun `priority increases reward`() {
        val normal = engine.score(record(priority = Priority.NORMAL)).xp
        val critical = engine.score(record(priority = Priority.CRITICAL)).xp
        assertTrue(critical > normal, "critical ($critical) should beat normal ($normal)")
        assertEquals(32L, critical) // 20 * 1.6
    }

    @Test
    fun `partial completion scales by fraction`() {
        val out = engine.score(record(result = CompletionResult.PARTIAL, fraction = 0.5, difficulty = Difficulty.HARD))
        assertEquals(18L, out.xp) // 35 * 0.5 = 17.5 -> 18
    }

    @Test
    fun `over-completion earns a bounded diminishing bonus, not linear xp`() {
        val target = engine.score(record(difficulty = Difficulty.MEDIUM, fraction = 1.0)).xp // 20
        val doubled = engine.score(record(difficulty = Difficulty.MEDIUM, fraction = 2.0)).xp // 2x target
        val quadruple = engine.score(record(difficulty = Difficulty.MEDIUM, fraction = 4.0)).xp
        // Going past the target earns *more*, but nowhere near 2x/4x — the bonus is capped.
        assertTrue(doubled > target, "over-completion should beat exactly hitting the target")
        assertTrue(doubled < 2 * target, "over-completion must not be linear (double effort != double xp)")
        // Default config caps the bonus at +50%, so even huge overshoots stay ≤ 1.5x base.
        assertTrue(quadruple <= (target * 1.5).toLong() + 1, "bonus is capped at +50%")
    }

    @Test
    fun `the over-completion bonus is zero for a normal completion`() {
        assertEquals(0.0, engine.overCompletionBonus(1.0), 1e-9)
        assertEquals(0.0, engine.overCompletionBonus(0.5), 1e-9)
        assertTrue(engine.overCompletionBonus(1.5) > 0.0)
    }

    @Test
    fun `repeating same easy quest yields diminishing returns`() {
        val first = engine.score(record(difficulty = Difficulty.EASY, priority = Priority.NORMAL)).xp
        val second = engine.score(
            record(difficulty = Difficulty.EASY),
            RewardContext(priorSameQuestCompletions = 1),
        ).xp
        val third = engine.score(
            record(difficulty = Difficulty.EASY),
            RewardContext(priorSameQuestCompletions = 2),
        ).xp
        assertTrue(first > second && second > third, "expected $first > $second > $third")
    }

    @Test
    fun `anti-farm never drops below the floor`() {
        val out = engine.score(
            record(difficulty = Difficulty.EASY),
            RewardContext(priorSameQuestCompletions = 50),
        )
        // floor 0.1 * base 10 = 1
        assertTrue(out.xp >= 1, "floor should keep at least 1 xp, got ${out.xp}")
    }

    @Test
    fun `meta maintenance is capped per day`() {
        val first = engine.score(
            record(category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.EASY),
            RewardContext(metaXpEarnedToday = 0),
        )
        assertTrue(first.xp <= 30)
        val atCap = engine.score(
            record(category = QuestCategory.META_MAINTENANCE, difficulty = Difficulty.HARD),
            RewardContext(metaXpEarnedToday = 30),
        )
        assertEquals(0L, atCap.xp)
        assertTrue(atCap.capReason != null)
    }

    @Test
    fun `consistency bonus increases with streak but is capped`() {
        val noStreak = engine.score(record(), RewardContext(currentStreakDays = 0)).xp
        val longStreak = engine.score(record(), RewardContext(currentStreakDays = 100)).xp
        assertTrue(longStreak > noStreak)
        // base 20, max bonus 25% -> at most 25
        assertTrue(longStreak <= 25, "consistency should be capped, got $longStreak")
    }

    @Test
    fun `missed quest applies gentle capped penalty`() {
        val out = engine.score(record(result = CompletionResult.FAILED))
        assertEquals(-3L, out.xp)
    }

    @Test
    fun `penalty cap prevents shame spiral`() {
        val out = engine.score(
            record(result = CompletionResult.SKIPPED),
            RewardContext(penaltyXpAppliedToday = 10),
        )
        assertEquals(0L, out.xp)
        assertTrue(out.capReason != null)
    }

    @Test
    fun `bad habit relapse is never punished and rewards honesty`() {
        val out = engine.score(
            record(result = CompletionResult.FAILED, category = QuestCategory.BAD_HABIT_REDUCTION),
        )
        assertEquals(3L, out.xp)
        assertTrue(out.xp > 0, "honesty should be positive")
    }

    @Test
    fun `rescheduling is neutral`() {
        val out = engine.score(record(result = CompletionResult.RESCHEDULED))
        assertEquals(0L, out.xp)
    }

    @Test
    fun `harder quests reward more than easier ones`() {
        val easy = engine.score(record(difficulty = Difficulty.EASY)).xp
        val hard = engine.score(record(difficulty = Difficulty.HARD)).xp
        val epic = engine.score(record(difficulty = Difficulty.EPIC)).xp
        assertTrue(easy < hard && hard < epic)
    }

    @Test
    fun `explanation is always present and human readable`() {
        val out = engine.score(record())
        assertTrue(out.explanation.isNotBlank())
        assertTrue(out.explanation.contains("XP"))
    }
}
