package com.questloop.app.data

import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import com.questloop.core.model.VerificationMethod
import com.questloop.app.data.local.QuestEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for entity <-> core-model mapping. No Android runtime needed:
 * the mappers only read fields and parse enum names.
 */
class MappersTest {

    @Test
    fun `quest round trips through entity`() {
        val quest = Quest(
            id = "q1",
            title = "Pay rent",
            category = QuestCategory.LIFE_ADMIN,
            frequency = QuestFrequency.MONTHLY,
            difficulty = Difficulty.MEDIUM,
            priority = Priority.CRITICAL,
            origin = QuestOrigin.USER_CREATED,
            estimatedMinutes = 15,
            deadlineEpochDay = 20000,
            tags = listOf("home", "money"),
            rationale = "Due on the 1st",
        )
        val restored = quest.toEntity().toModel()
        assertEquals(quest, restored)
    }

    @Test
    fun `quest with empty tags round trips`() {
        val quest = Quest(
            id = "q2",
            title = "Stretch",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.TRIVIAL,
        )
        val restored = quest.toEntity().toModel()
        assertEquals(emptyList<String>(), restored.tags)
        assertEquals(quest, restored)
    }

    @Test
    fun `archived flag is carried onto the entity`() {
        val quest = Quest(
            id = "q3",
            title = "Old quest",
            category = QuestCategory.CHORES,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.EASY,
        )
        assertTrue(quest.toEntity(archived = true).archived)
        assertEquals(false, quest.toEntity().archived)
    }

    @Test
    fun `unknown enum names fall back to safe defaults instead of crashing`() {
        // Forward/backward compatibility: a stored value the current build doesn't
        // know must not throw — it maps to the documented default.
        val entity = QuestEntity(
            id = "q4",
            title = "From a future version",
            category = "BOGUS_CATEGORY",
            frequency = "BOGUS_FREQ",
            difficulty = "BOGUS_DIFF",
            priority = "BOGUS_PRIO",
            origin = "BOGUS_ORIGIN",
            estimatedMinutes = 10,
            deadlineEpochDay = null,
            isReductionQuest = false,
            completionStyle = "BOGUS_STYLE",
            targetCount = null,
            unit = null,
            tags = "",
            rationale = null,
        )
        val model = entity.toModel()
        assertEquals(QuestCategory.LIFE_ADMIN, model.category)
        assertEquals(QuestFrequency.ONE_OFF, model.frequency)
        assertEquals(Difficulty.MEDIUM, model.difficulty)
        assertEquals(Priority.NORMAL, model.priority)
        assertEquals(QuestOrigin.USER_CREATED, model.origin)
        assertEquals(com.questloop.core.model.CompletionStyle.BINARY, model.completionStyle)
    }

    @Test
    fun `schedule fields round trip through entity`() {
        val quest = Quest(
            id = "q5",
            title = "Take medicine",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.TRIVIAL,
            scheduledTimes = listOf(8 * 60, 20 * 60),
            totalOccurrences = 5,
            remindersEnabled = true,
        )
        assertEquals(quest, quest.toEntity().toModel())

        val anchored = quest.copy(
            frequency = QuestFrequency.WEEKLY,
            scheduledDayOfWeek = java.time.DayOfWeek.MONDAY,
            scheduledDayOfMonth = null,
        )
        assertEquals(anchored, anchored.toEntity().toModel())
    }

    @Test
    fun `out-of-range stored day-of-week degrades to no anchor`() {
        val entity = Quest(
            id = "q6",
            title = "Weekly",
            category = QuestCategory.CHORES,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.EASY,
        ).toEntity().copy(scheduledDayOfWeek = 9)
        assertEquals(null, entity.toModel().scheduledDayOfWeek)
    }

    @Test
    fun `completion record round trips and preserves xp`() {
        val record = CompletionRecord(
            instanceId = "q1@100",
            questId = "q1",
            category = QuestCategory.BAD_HABIT_REDUCTION,
            difficulty = Difficulty.HARD,
            priority = Priority.HIGH,
            result = CompletionResult.PARTIAL,
            verification = VerificationMethod.TIMER,
            epochDay = 100,
            fraction = 0.5,
            isMeta = false,
            xpAwarded = 42,
        )
        val entity = record.toEntity()
        assertEquals(42L, entity.xpAwarded)
        assertEquals(record, entity.toModel())
    }
}
