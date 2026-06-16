package com.questloop.app.ui.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.questloop.core.generation.QuestGenerator
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestInstance
import com.questloop.core.safety.SafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the stateless [TodayContent]. Run on the JVM via
 * Robolectric so they execute in CI without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
// Tall screen so list content (e.g. a quest card's "Log progress" button) is
// on-screen and clickable; otherwise off-screen taps are silently dropped.
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class TodayContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun quest(
        id: String,
        title: String,
        style: CompletionStyle = CompletionStyle.BINARY,
        target: Int? = null,
        unit: String? = null,
        category: QuestCategory = QuestCategory.WORK_STUDY,
        rationale: String? = null,
    ) = Quest(
        id = id,
        title = title,
        category = category,
        frequency = QuestFrequency.DAILY,
        difficulty = Difficulty.MEDIUM,
        completionStyle = style,
        targetCount = target,
        unit = unit,
        rationale = rationale,
    )

    private fun stateWith(vararg quests: Quest): TodayUiState {
        val instances = quests.map { QuestInstance("${it.id}@1", it, 1) }
        return TodayUiState(
            loading = false,
            plan = QuestGenerator.DailyPlan(
                epochDay = 1,
                quests = instances,
                totalEstimatedMinutes = instances.sumOf { it.quest.estimatedMinutes },
                deferred = emptyList(),
                notes = emptyList(),
            ),
            totalXp = 120,
            level = 2,
            levelProgress = 0.3,
        )
    }

    private fun noopActions(
        onComplete: (Quest) -> Unit = {},
        onSkip: (Quest) -> Unit = {},
        onCompleteMeasured: (Quest, Int) -> Unit = { _, _ -> },
        onCheckIn: (Int, Int) -> Unit = { _, _ -> },
    ) = TodayActions(onComplete, onSkip, onCompleteMeasured, onCheckIn)

    @Test
    fun `binary quest shows title and complete fires callback`() {
        var completed: Quest? = null
        composeRule.setContent {
            TodayContent(
                state = stateWith(quest("q1", "Write the report")),
                actions = noopActions(onComplete = { completed = it }),
            )
        }
        composeRule.onNodeWithText("Write the report").assertIsDisplayed()
        composeRule.onNodeWithText("Complete").performClick()
        assertEquals("q1", completed?.id)
    }

    @Test
    fun `rationale is hidden until Why is tapped`() {
        composeRule.setContent {
            TodayContent(
                state = stateWith(quest("q1", "Write the report", rationale = "It's due Friday")),
                actions = noopActions(),
            )
        }
        composeRule.onNodeWithText("It's due Friday").assertDoesNotExist()
        composeRule.onNodeWithText("Why?").performClick()
        composeRule.onNodeWithText("It's due Friday").assertIsDisplayed()
    }

    @Test
    fun `focus mode collapses the list to the next quest`() {
        composeRule.setContent {
            TodayContent(
                state = stateWith(quest("a", "First quest"), quest("b", "Second quest")),
                actions = noopActions(),
            )
        }
        composeRule.onNodeWithText("First quest").assertIsDisplayed()
        composeRule.onNodeWithText("Second quest").assertIsDisplayed()
        composeRule.onNodeWithText("Focus").performClick()
        composeRule.onNodeWithText("First quest").assertIsDisplayed()
        composeRule.onNodeWithText("Second quest").assertDoesNotExist()
    }

    @Test
    fun `safety signal renders as a single banner`() {
        val state = stateWith(quest("q1", "Anything")).copy(
            signals = listOf(
                SafetyGuard.Signal("REST", SafetyGuard.Severity.SUGGESTION, "Consider a rest day"),
            ),
        )
        composeRule.setContent { TodayContent(state = state, actions = noopActions()) }
        composeRule.onNodeWithText("Consider a rest day", substring = true).assertIsDisplayed()
    }

    @Test
    fun `reduction quest shows log honestly instead of skip`() {
        composeRule.setContent {
            TodayContent(
                state = stateWith(
                    quest("bad", "Stay under scroll limit", category = QuestCategory.BAD_HABIT_REDUCTION),
                ),
                actions = noopActions(),
            )
        }
        composeRule.onNodeWithText("Log honestly").assertIsDisplayed()
    }

    @Test
    fun `quantitative control increments the displayed progress`() {
        composeRule.setContent {
            TodayContent(
                state = stateWith(
                    quest("water", "Stay hydrated", CompletionStyle.QUANTITATIVE, target = 8, unit = "glasses"),
                ),
                actions = noopActions(),
            )
        }
        composeRule.onNodeWithText("0 / 8 glasses").assertIsDisplayed()
        composeRule.onNodeWithText("+").performClick()
        composeRule.onNodeWithText("1 / 8 glasses").assertIsDisplayed()
    }

    @Test
    fun `quantitative log fires the measured-completion callback`() {
        // The count→XP math is covered by CompletionScalingTest; this verifies
        // the "Log progress" control is wired through to onCompleteMeasured.
        var measured: Pair<String, Int>? = null
        composeRule.setContent {
            TodayContent(
                state = stateWith(
                    quest("water", "Stay hydrated", CompletionStyle.QUANTITATIVE, target = 8, unit = "glasses"),
                ),
                actions = noopActions(onCompleteMeasured = { q, v -> measured = q.id to v }),
            )
        }
        composeRule.onNodeWithText("Log progress").performScrollTo().performClick()
        assertEquals("water", measured?.first)
    }

    @Test
    fun `subjective quest shows rating buttons`() {
        var measured: Int? = null
        composeRule.setContent {
            TodayContent(
                state = stateWith(quest("create", "Creative work", CompletionStyle.SUBJECTIVE)),
                actions = noopActions(onCompleteMeasured = { _, v -> measured = v }),
            )
        }
        composeRule.onNodeWithText("How did it go?").assertIsDisplayed()
        composeRule.onNodeWithText("4").performClick()
        assertEquals(4, measured)
    }

    @Test
    fun `empty plan shows all clear`() {
        composeRule.setContent {
            TodayContent(
                state = TodayUiState(loading = false, plan = stateWith().plan!!.copy(quests = emptyList())),
                actions = noopActions(),
            )
        }
        composeRule.onNodeWithText("All clear ✓").assertIsDisplayed()
    }

    @Test
    fun `energy check-in fires callback`() {
        var picked: Pair<Int, Int>? = null
        composeRule.setContent {
            TodayContent(
                state = stateWith(quest("q1", "Anything")),
                actions = noopActions(onCheckIn = { e, m -> picked = e to m }),
            )
        }
        composeRule.onNodeWithText("🔋 Low").performClick()
        assertTrue(picked != null)
        assertEquals(2, picked?.first)
    }
}
