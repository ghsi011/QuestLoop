package com.questloop.core.generation

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.DayPart
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineQuestFactoryTest {

    @Test
    fun `morning offers a single instant review micro-quest`() {
        val routines = RoutineQuestFactory.routinesFor(DayPart.MORNING)
        assertEquals(1, routines.size)
        val q = routines.single()
        assertEquals(RoutineQuestFactory.MORNING_REVIEW, q.id)
        assertEquals(Difficulty.TRIVIAL, q.difficulty)
        assertTrue(q.category.isMeta)
        assertTrue(q.estimatedMinutes <= 1)
    }

    @Test
    fun `evening offers exactly the two wrap-up admin quests`() {
        val routines = RoutineQuestFactory.routinesFor(DayPart.EVENING)
        assertEquals(
            listOf(RoutineQuestFactory.EVENING_REVIEW, RoutineQuestFactory.EVENING_INTAKE),
            routines.map { it.id },
        )
        // The whole evening loop should be ~2-3 minutes.
        assertTrue(routines.sumOf { it.estimatedMinutes } <= 3)
    }

    @Test
    fun `midday has no routine - minimal interaction`() {
        assertTrue(RoutineQuestFactory.routinesFor(DayPart.MIDDAY).isEmpty())
    }

    @Test
    fun `day part maps from hour`() {
        assertEquals(DayPart.MORNING, DayPart.fromHour(7))
        assertEquals(DayPart.MIDDAY, DayPart.fromHour(13))
        assertEquals(DayPart.EVENING, DayPart.fromHour(20))
    }
}

class RoutineGenerationIntegrationTest {

    private val generator = QuestGenerator()

    private fun completion(questId: String, day: Long) = CompletionRecord(
        instanceId = "$questId@$day",
        questId = questId,
        category = QuestCategory.META_MAINTENANCE,
        difficulty = Difficulty.TRIVIAL,
        priority = Priority.NORMAL,
        result = CompletionResult.COMPLETED,
        epochDay = day,
        fraction = 1.0,
        isMeta = true,
    )

    @Test
    fun `routines are always scheduled first and survive a full candidate list`() {
        val candidates = (1..10).map {
            com.questloop.core.model.Quest(
                id = "c$it",
                title = "Candidate $it",
                category = QuestCategory.WORK_STUDY,
                frequency = com.questloop.core.model.QuestFrequency.ONE_OFF,
                difficulty = Difficulty.MEDIUM,
            )
        }
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = UserProfile(preferences = UserPreferences(maxDailyQuests = 4, defaultAvailableMinutes = 1000)),
                candidates = candidates,
                routineQuests = RoutineQuestFactory.routinesFor(DayPart.EVENING),
            ),
        )
        val ids = plan.quests.map { it.quest.id }
        assertTrue(RoutineQuestFactory.EVENING_REVIEW in ids)
        assertTrue(RoutineQuestFactory.EVENING_INTAKE in ids)
    }

    @Test
    fun `a completed routine does not reappear the same day`() {
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = UserProfile(),
                candidates = emptyList(),
                history = listOf(completion(RoutineQuestFactory.MORNING_REVIEW, 100)),
                routineQuests = RoutineQuestFactory.routinesFor(DayPart.MORNING),
            ),
        )
        assertTrue(plan.quests.none { it.quest.id == RoutineQuestFactory.MORNING_REVIEW })
    }
}
