package com.questloop.app.ui.completed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.questloop.app.data.QuestRepository
import com.questloop.core.model.CompletionRecord
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the stateless [CompletedContent] (the Completed-history
 * screen body). Run on the JVM via Robolectric so they execute in CI without an
 * emulator. Behaviour of the actions themselves is asserted by
 * [CompletedViewModelTest]; here we verify the rendering, the editable gating, and
 * that each control fires its callback.
 */
@RunWith(RobolectricTestRunner::class)
// A normal phone height (not an extra-tall qualifier): one card and its buttons fit,
// and the edit AlertDialog measures against a realistic window (an extra-tall window
// blows the dialog's measurement up into an OutOfMemoryError under Robolectric).
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CompletedContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun quest(id: String, title: String) = Quest(
        id = id,
        title = title,
        category = QuestCategory.WORK_STUDY,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.EASY,
    )

    private fun entry(
        id: String,
        title: String,
        editable: Boolean = true,
        quest: Quest? = quest(id, title),
    ) = QuestRepository.CompletedEntry(
        record = CompletionRecord(
            instanceId = "$id@1",
            questId = id,
            category = QuestCategory.WORK_STUDY,
            difficulty = Difficulty.EASY,
            priority = Priority.NORMAL,
            result = CompletionResult.COMPLETED,
            epochDay = 1,
            xpAwarded = 12,
        ),
        title = title,
        quest = quest,
        editable = editable,
    )

    private fun stateWith(vararg entries: QuestRepository.CompletedEntry, editing: EditTarget? = null) =
        CompletedUiState(loading = false, entries = entries.toList(), editing = editing)

    private fun render(
        state: CompletedUiState,
        onSetFilter: (HistoryFilter) -> Unit = {},
        onUndo: (QuestRepository.CompletedEntry) -> Unit = {},
        onStartEdit: (QuestRepository.CompletedEntry) -> Unit = {},
        onReadd: (QuestRepository.CompletedEntry) -> Unit = {},
        onSaveEdit: (Quest) -> Unit = {},
        onCancelEdit: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompletedContent(
                state = state,
                onSetFilter = onSetFilter,
                onUndo = onUndo,
                onStartEdit = onStartEdit,
                onReadd = onReadd,
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
            )
        }
    }

    @Test
    fun `renders an entry with its title and xp and fires the filter callback`() {
        var picked: HistoryFilter? = null
        render(stateWith(entry("a", "Write the report")), onSetFilter = { picked = it })
        composeRule.onNodeWithText("Write the report").assertIsDisplayed()
        composeRule.onNodeWithText("+12 XP").assertIsDisplayed()
        composeRule.onNodeWithText("All time").performClick()
        assertEquals(HistoryFilter.ALL, picked)
    }

    @Test
    fun `empty window shows the hint`() {
        render(stateWith())
        composeRule.onNodeWithText("Nothing completed", substring = true).assertIsDisplayed()
    }

    @Test
    fun `undo and re-add fire their callbacks and start-edit opens for a stored quest`() {
        var undone: String? = null
        var readded: String? = null
        var editing: String? = null
        render(
            stateWith(entry("a", "Write the report")),
            onUndo = { undone = it.record.questId },
            onReadd = { readded = it.record.questId },
            onStartEdit = { editing = it.record.questId },
        )
        composeRule.onNodeWithText("Undo").performClick()
        composeRule.onNodeWithText("Re-add").performClick()
        composeRule.onNodeWithText("Edit").performClick()
        assertEquals("a", undone)
        assertEquals("a", readded)
        assertEquals("a", editing)
    }

    @Test
    fun `edit and re-add are disabled for a non-editable entry`() {
        render(stateWith(entry("routine", "Evening wrap-up", editable = false)))
        composeRule.onNodeWithText("Undo").assertIsEnabled()
        composeRule.onNodeWithText("Edit").assertIsNotEnabled()
        composeRule.onNodeWithText("Re-add").assertIsNotEnabled()
    }

    // The edit dialog's field body is tested window-free (the enclosing AlertDialog's
    // Dialog window can't be driven under Robolectric — it OOMs). The chips + title
    // field + the Quest it composes all live in EditQuestFields.
    @Test
    fun `edit fields render the chips and re-scoring difficulty emits the edited quest`() {
        var edited: Quest? = null
        composeRule.setContent {
            EditQuestFields(original = quest("a", "Write the report"), onChange = { edited = it })
        }
        composeRule.onNodeWithText("Difficulty (sets XP)").assertIsDisplayed()
        composeRule.onNodeWithText("Hard").performClick()
        assertEquals(Difficulty.HARD, edited?.difficulty)
        assertEquals("Write the report", edited?.title)
    }

    @Test
    fun `choosing the bad-habit-reduction category marks the edited quest as a reduction quest`() {
        var edited: Quest? = null
        composeRule.setContent {
            EditQuestFields(original = quest("a", "Cut back on scrolling"), onChange = { edited = it })
        }
        composeRule.onNodeWithText("Bad habit reduction").performClick()
        assertEquals(QuestCategory.BAD_HABIT_REDUCTION, edited?.category)
        assertTrue("reduction category should flag the quest", edited?.isReductionQuest == true)
    }
}
