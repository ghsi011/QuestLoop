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
import org.junit.Assert.assertEquals
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
