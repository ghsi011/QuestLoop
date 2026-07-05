package com.questloop.app.ui.components

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the user-facing vocabulary of the add/edit pickers (docs/CONTENT_STYLE.md:
 * no developer language). The internal enum values stay valid on stored quests
 * and AI suggestions — they're just never offered as choices.
 */
class PickerVocabularyTest {

    @Test
    fun `completion styles get plain labels, never the enum names`() {
        assertEquals("Done or not", CompletionStyle.BINARY.pretty())
        assertEquals("Count", CompletionStyle.QUANTITATIVE.pretty())
        assertEquals("Time", CompletionStyle.DURATION.pretty())
        assertEquals("Rate 1-5", CompletionStyle.SUBJECTIVE.pretty())
    }

    @Test
    fun `category picker offers every category except the internal meta bucket`() {
        assertFalse(pickableCategories.contains(QuestCategory.META_MAINTENANCE))
        assertEquals(QuestCategory.entries.size - 1, pickableCategories.size)
    }

    @Test
    fun `frequency picker offers only the user-meaningful cadences`() {
        assertEquals(
            listOf(
                QuestFrequency.DAILY,
                QuestFrequency.WEEKLY,
                QuestFrequency.MONTHLY,
                QuestFrequency.ONE_OFF,
            ),
            pickableFrequencies,
        )
    }
}
