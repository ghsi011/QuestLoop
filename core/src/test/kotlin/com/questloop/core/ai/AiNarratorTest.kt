package com.questloop.core.ai

import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.Difficulty
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestInstance
import com.questloop.core.review.ReviewGenerator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiNarratorTest {

    private fun narrator(response: String? = null, fail: Boolean = false) = AiNarrator(
        client = object : LlmClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String): String {
                if (fail) throw RuntimeException("network down")
                return response ?: ""
            }
        },
    )

    private val review = ReviewGenerator.Review(
        periodLabel = "This week",
        totalCompleted = 14,
        totalAttempted = 20,
        xpEarned = 280,
        activeDays = 6,
        completionRate = 0.7,
        byCategory = listOf(
            ReviewGenerator.CategoryStat(QuestCategory.HEALTH, 7, 8, 140),
            ReviewGenerator.CategoryStat(QuestCategory.WORK_STUDY, 3, 8, 60),
        ),
        mostNeglectedCategory = QuestCategory.WORK_STUDY,
        strongestCategory = QuestCategory.HEALTH,
        highlights = emptyList(),
        suggestions = emptyList(),
    )

    private fun plan(vararg quests: Quest) = QuestGenerator.DailyPlan(
        epochDay = 100,
        quests = quests.map { QuestInstance("${it.id}@100", it, 100) },
        totalEstimatedMinutes = quests.sumOf { it.estimatedMinutes },
        deferred = emptyList(),
        notes = emptyList(),
    )

    private fun quest(id: String, cat: QuestCategory, min: Int) = Quest(
        id = id, title = "secret-$id", category = cat, frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM, estimatedMinutes = min,
    )

    @Test
    fun `clean ai review is used as-is`() = runTest {
        val n = narrator("14 of 20 done across 6 days, with health carrying it at 7 of 8.")
        val out = n.narrateReview(review)
        assertTrue(out.fromAi)
        assertEquals("14 of 20 done across 6 days, with health carrying it at 7 of 8.", out.text)
    }

    @Test
    fun `sloppy ai review falls back to terse facts`() = runTest {
        val n = narrator("Amazing job this week — you're absolutely crushing it! Keep it up, superstar! 🎉")
        val out = n.narrateReview(review)
        assertFalse(out.fromAi)
        assertEquals(AiNarrator.reviewFallback(review), out.text)
        // The fallback is specific and slop-free.
        assertTrue(out.text.contains("14 of 20"))
        assertTrue(NarrationSanitizer.gate(out.text, NarrationSanitizer.Mode.REVIEW).accepted)
    }

    @Test
    fun `disabling the filter shows raw model output`() = runTest {
        val slop = "Amazing job — you're absolutely crushing it! 🎉"
        val out = narrator(slop).narrateReview(review, sanitize = false)
        assertTrue(out.fromAi)
        assertEquals(slop, out.text)
    }

    @Test
    fun `disabled filter still falls back on empty output`() = runTest {
        val out = narrator("   ").narrateReview(review, sanitize = false)
        assertFalse(out.fromAi)
        assertEquals(AiNarrator.reviewFallback(review), out.text)
    }

    @Test
    fun `transport failure falls back`() = runTest {
        val out = narrator(fail = true).narrateReview(review)
        assertFalse(out.fromAi)
        assertEquals("14 of 20 done over 6 active days. Strongest area: health. work study ran low at 38%.", out.text)
    }

    @Test
    fun `clean ai rationale is used as-is`() = runTest {
        val facts = AiNarrator.PlanFacts.from(
            plan(quest("a", QuestCategory.HEALTH, 20), quest("b", QuestCategory.LIFE_ADMIN, 20)),
            checkIn = EnergyCheckIn(100, energy = 2, availableMinutes = 45),
            availableMinutes = 45,
            epochDay = 100,
        )
        val n = narrator("Energy's low, so it's two small things under an hour, nothing heavy.")
        val out = n.rationale(facts)
        assertTrue(out.fromAi)
        assertEquals("Energy's low, so it's two small things under an hour, nothing heavy.", out.text)
    }

    @Test
    fun `sloppy ai rationale falls back to terse facts`() = runTest {
        val facts = AiNarrator.PlanFacts.from(
            plan(quest("a", QuestCategory.HEALTH, 20), quest("b", QuestCategory.LIFE_ADMIN, 20)),
            checkIn = EnergyCheckIn(100, energy = 2, availableMinutes = 45),
            availableMinutes = 45,
            epochDay = 100,
        )
        val n = narrator("Let's crush today and embrace the journey! You've got this 💪")
        val out = n.rationale(facts)
        assertFalse(out.fromAi)
        assertEquals("Lighter day on low energy: 2 smaller things, about 40 minutes.", out.text)
        assertTrue(NarrationSanitizer.gate(out.text, NarrationSanitizer.Mode.RATIONALE).accepted)
    }

    @Test
    fun `plan facts derive energy, mix and due without titles`() {
        val overdue = quest("a", QuestCategory.HEALTH, 20).copy(deadlineEpochDay = 99)
        val facts = AiNarrator.PlanFacts.from(
            plan(overdue, quest("b", QuestCategory.WORK_STUDY, 30)),
            checkIn = EnergyCheckIn(100, energy = 5, availableMinutes = 200),
            availableMinutes = 200,
            epochDay = 100,
        )
        assertEquals(5, facts.energy)
        assertFalse(facts.lowEnergyDay)
        assertEquals("overdue", facts.due)
        assertEquals(2, facts.tasksToday)
        // No quest titles leak into the model payload.
        val payload = narrator().planPayload(facts)
        assertFalse(payload.contains("secret-"))
    }

    @Test
    fun `review payload sends aggregates not titles`() {
        val payload = narrator().reviewPayload(review)
        assertTrue(payload.contains("completed: 14"))
        assertTrue(payload.contains("health"))
        assertFalse(payload.contains("secret-"))
    }
}
