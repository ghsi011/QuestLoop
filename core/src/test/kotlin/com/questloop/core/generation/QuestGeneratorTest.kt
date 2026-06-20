package com.questloop.core.generation

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuestGeneratorTest {

    private val generator = QuestGenerator()

    private fun quest(
        id: String,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        difficulty: Difficulty = Difficulty.MEDIUM,
        priority: Priority = Priority.NORMAL,
        minutes: Int = 25,
        deadline: Long? = null,
    ) = Quest(
        id = id,
        title = "Quest $id",
        category = category,
        frequency = QuestFrequency.ONE_OFF,
        difficulty = difficulty,
        priority = priority,
        estimatedMinutes = minutes,
        deadlineEpochDay = deadline,
    )

    private fun profile(prefs: UserPreferences = UserPreferences()) = UserProfile(preferences = prefs)

    @Test
    fun `respects max daily quests`() {
        // Diverse categories so the count limit (not the variety cap) is exercised.
        val cats = QuestCategory.entries.filterNot { it.isMeta }
        val candidates = (1..20).map { quest("q$it", category = cats[it % cats.size], minutes = 5) }
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 5, defaultAvailableMinutes = 1000)),
                candidates = candidates,
            ),
        )
        assertEquals(5, plan.quests.size)
        assertTrue(plan.deferred.isNotEmpty())
    }

    @Test
    fun `without a check-in the count governs and time is only advisory`() {
        // 8 quests of 30m each = 240m, well over the 120m default budget. With no
        // check-in, the user's max-per-day (8) should still be honoured.
        val cats = QuestCategory.entries.filterNot { it.isMeta }
        val candidates = (1..12).map { quest("q$it", category = cats[it % cats.size], minutes = 30) }
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 8, defaultAvailableMinutes = 120)),
                candidates = candidates,
            ),
        )
        assertEquals(8, plan.quests.size)
        assertTrue(plan.notes.any { it.contains("time", ignoreCase = true) }, "should note the long plan")
    }

    @Test
    fun `respects available time budget`() {
        val candidates = (1..10).map { quest("q$it", minutes = 30) }
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 10)),
                candidates = candidates,
                checkIn = EnergyCheckIn(100, energy = 3, availableMinutes = 60),
            ),
        )
        assertTrue(plan.totalEstimatedMinutes <= 60 + 30, "should not greatly exceed budget")
        assertTrue(plan.quests.size <= 3)
    }

    @Test
    fun `urgent deadlines are prioritised`() {
        val candidates = listOf(
            quest("far", deadline = 200),
            quest("overdue", deadline = 90),
            quest("today", deadline = 100),
        )
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 1, defaultAvailableMinutes = 1000)),
                candidates = candidates,
            ),
        )
        assertEquals("overdue", plan.quests.first().quest.id)
    }

    @Test
    fun `low energy day reduces load and excludes hard quests`() {
        val candidates = listOf(
            quest("hard1", difficulty = Difficulty.HARD),
            quest("epic1", difficulty = Difficulty.EPIC),
            quest("easy1", difficulty = Difficulty.EASY, minutes = 10),
            quest("med1", difficulty = Difficulty.MEDIUM, minutes = 15),
        )
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 6, defaultAvailableMinutes = 1000)),
                candidates = candidates,
                checkIn = EnergyCheckIn(100, energy = 2, availableMinutes = 1000),
            ),
        )
        assertTrue(plan.quests.none { it.quest.difficulty.ordinal > Difficulty.MEDIUM.ordinal })
        assertTrue(plan.notes.any { it.contains("Low-energy", ignoreCase = true) })
    }

    @Test
    fun `rest day schedules only routines and frames it as recovery`() {
        val candidates = listOf(
            quest("easy1", difficulty = Difficulty.EASY, minutes = 10),
            quest("med1", difficulty = Difficulty.MEDIUM, minutes = 15),
        )
        val routine = quest("routine", category = QuestCategory.META_MAINTENANCE, minutes = 1)
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 6, defaultAvailableMinutes = 1000)),
                candidates = candidates,
                checkIn = EnergyCheckIn(100, energy = 1, availableMinutes = 1000),
                routineQuests = listOf(routine),
            ),
        )
        // Only the routine is scheduled — no real quests, even though they fit the budget.
        assertEquals(listOf("routine"), plan.quests.map { it.quest.id })
        assertTrue(plan.notes.any { it.contains("Rest day", ignoreCase = true) })
        // The "deferred to keep today realistic" note must not undercut the rest framing.
        assertTrue(plan.notes.none { it.contains("deferred", ignoreCase = true) })
    }

    @Test
    fun `rest day with no routines still schedules nothing`() {
        val candidates = listOf(quest("easy1", difficulty = Difficulty.EASY, minutes = 10))
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 6, defaultAvailableMinutes = 1000)),
                candidates = candidates,
                checkIn = EnergyCheckIn(100, energy = 1, availableMinutes = 1000),
            ),
        )
        // The "never empty" fallback is intentionally skipped on a rest day.
        assertTrue(plan.quests.isEmpty())
    }

    @Test
    fun `avoided quests resurface`() {
        val history = (1..3).map {
            CompletionRecord(
                instanceId = "avoid@9$it",
                questId = "avoid",
                category = QuestCategory.LIFE_ADMIN,
                difficulty = Difficulty.EASY,
                priority = Priority.NORMAL,
                result = CompletionResult.SKIPPED,
                epochDay = (90 + it).toLong(),
            )
        }
        val candidates = listOf(
            quest("normal", category = QuestCategory.WORK_STUDY),
            quest("avoid", category = QuestCategory.LIFE_ADMIN, difficulty = Difficulty.EASY),
        )
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 1, defaultAvailableMinutes = 1000)),
                candidates = candidates,
                history = history,
            ),
        )
        assertEquals("avoid", plan.quests.first().quest.id)
    }

    @Test
    fun `variety cap prevents single-category domination`() {
        val candidates = (1..10).map { quest("chore$it", category = QuestCategory.CHORES, minutes = 5) } +
            quest("work", category = QuestCategory.WORK_STUDY, minutes = 5)
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 6, defaultAvailableMinutes = 1000)),
                candidates = candidates,
            ),
        )
        val choreCount = plan.quests.count { it.quest.category == QuestCategory.CHORES }
        assertTrue(choreCount <= 4, "chores should be capped for variety, was $choreCount")
    }

    @Test
    fun `at most one meta quest is scheduled`() {
        val candidates = (1..5).map {
            quest("meta$it", category = QuestCategory.META_MAINTENANCE, minutes = 2)
        }
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(maxDailyQuests = 6, defaultAvailableMinutes = 1000)),
                candidates = candidates,
            ),
        )
        assertEquals(1, plan.quests.count { it.quest.category == QuestCategory.META_MAINTENANCE })
    }

    @Test
    fun `never returns empty plan when candidates exist`() {
        val candidates = listOf(quest("only", minutes = 999))
        val plan = generator.generateDaily(
            QuestGenerator.Request(
                epochDay = 100,
                profile = profile(UserPreferences(defaultAvailableMinutes = 10)),
                candidates = candidates,
            ),
        )
        assertEquals(1, plan.quests.size)
    }
}
