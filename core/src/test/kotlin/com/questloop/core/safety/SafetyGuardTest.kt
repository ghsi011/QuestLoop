package com.questloop.core.safety

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafetyGuardTest {

    private val guard = SafetyGuard()

    private fun rec(
        day: Long,
        result: CompletionResult = CompletionResult.COMPLETED,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        id: String = "q",
    ) = CompletionRecord(
        instanceId = "$id@$day",
        questId = id,
        category = category,
        difficulty = Difficulty.MEDIUM,
        priority = Priority.NORMAL,
        result = result,
        epochDay = day,
        fraction = if (result == CompletionResult.COMPLETED) 1.0 else 0.0,
        isMeta = category.isMeta,
    )

    @Test
    fun `no signals for a balanced light week`() {
        val records = listOf(rec(100), rec(99), rec(98))
        val active = setOf(98L, 99L, 100L)
        val signals = guard.evaluate(records, active, today = 100)
        assertTrue(signals.none { it.severity == SafetyGuard.Severity.WARNING })
    }

    @Test
    fun `suggests rest after long active streak`() {
        val active = (90L..100L).toSet() // 11 days
        val signals = guard.evaluate(emptyList(), active, today = 100)
        assertTrue(signals.any { it.code == "REST_SUGGESTION" })
    }

    @Test
    fun `warns on overdrive day`() {
        val records = (1..14).map { rec(100, id = "q$it") }
        val signals = guard.evaluate(records, setOf(100L), today = 100)
        val overdrive = signals.firstOrNull { it.code == "OVERDRIVE" }
        assertTrue(overdrive != null)
        assertEquals(SafetyGuard.Severity.WARNING, overdrive!!.severity)
    }

    @Test
    fun `flags meta-heavy progress`() {
        val records = (1..8).map {
            rec(100 - it.toLong() % 5, category = QuestCategory.META_MAINTENANCE, id = "m$it")
        }
        val signals = guard.evaluate(records, setOf(96L, 97L, 98L, 99L, 100L), today = 100)
        assertTrue(signals.any { it.code == "META_HEAVY" })
    }

    @Test
    fun `switches to recovery framing during a rough patch`() {
        val records = (1..6).map {
            rec(100 - it.toLong(), result = CompletionResult.SKIPPED, id = "s$it")
        }
        val signals = guard.evaluate(records, emptySet(), today = 100)
        assertTrue(signals.any { it.code == "RECOVERY_MODE" })
    }

    @Test
    fun `consecutive active streak counts correctly`() {
        val active = setOf(97L, 98L, 99L, 100L)
        assertEquals(4, guard.consecutiveActiveStreak(active, today = 100))
    }
}
