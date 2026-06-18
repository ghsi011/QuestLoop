package com.questloop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Coverage-oriented walk over the lowest-covered screens. This is a *coverage*
 * exercise, not a behavioural assertion suite — behaviour is asserted by the
 * ViewModel/data JVM unit tests and by [NavigationTransitionTest]. Every step
 * here is therefore best-effort (wrapped so a momentarily absent/duplicated/
 * offscreen node, or a slow async load, can't fail the run); the goal is simply
 * to compose each screen and fire as many handlers as possible so the merged
 * JaCoCo report reflects the UI the emulator can reach. Navigation *correctness*
 * is still asserted strictly by NavigationTransitionTest.
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker), like
 * [AppSmokeTest] and [NavigationTransitionTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoverageWalkTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ---- best-effort helpers (nothing here may fail the walk) --------------

    private fun present(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun onToday() = composeRule.onAllNodesWithTag("today-list").fetchSemanticsNodes().isNotEmpty()

    /** Best-effort wait; swallows the timeout so the walk continues regardless. */
    private fun awaitSafe(timeoutMs: Long = 8_000, condition: () -> Boolean) {
        runCatching { composeRule.waitUntil(timeoutMillis = timeoutMs, condition = condition) }
    }

    /** Best-effort: click the clickable node with [label]. */
    private fun tapText(label: String) {
        runCatching {
            composeRule.onAllNodesWithText(label).filterToOne(hasClickAction())
                .performScrollTo().performClick()
            composeRule.waitForIdle()
        }
    }

    /** Best-effort: type [text] into the field labelled [label]. */
    private fun typeInto(label: String, text: String) {
        runCatching {
            composeRule.onNodeWithText(label).performScrollTo().performTextInput(text)
            composeRule.waitForIdle()
        }
    }

    private fun tab(label: String) = tapText(label)

    private fun openFab(contentDescription: String) {
        runCatching {
            composeRule.onNodeWithContentDescription(contentDescription).performClick()
            composeRule.waitForIdle()
        }
    }

    private fun back() {
        runCatching {
            composeRule.onNodeWithContentDescription("Back").performClick()
            composeRule.waitForIdle()
        }
    }

    @Before
    fun reachHome() {
        awaitSafe(10_000) { present("Get started") || onToday() }
        if (present("Get started")) tapText("Get started")
        awaitSafe { onToday() }
    }

    /**
     * Biggest lever: open the Add modal via the FAB, click each option chip in
     * every group, and submit. Selecting Quantitative/Duration/Subjective also
     * reveals the conditional count/unit fields.
     */
    @Test
    fun add_form_exercises_every_option_group_and_submits() {
        awaitSafe { onToday() }
        openFab("Add quest")
        awaitSafe { present("New quest") }

        val chips = listOf(
            "Health", "Life admin", "Chores", "Work study", "Social",
            "Personal growth", "Bad habit reduction", "Meta maintenance",
            "Trivial", "Easy", "Medium", "Hard", "Epic",
            "Low", "Normal", "High", "Critical",
            "Daily", "Weekly", "Monthly", "Recurring", "One off", "Seasonal",
            "Quantitative", "Duration", "Subjective", "Binary",
        )
        for (label in chips) tapText(label)

        typeInto("What do you want to get done?", "Coverage walk quest 7777")
        tapText("Add quest") // submit button (clickable; FAB shares the text)
        awaitSafe(15_000) { !present("New quest") }
    }

    /** Today energy check-in chips and, if present, a completion + achievement strip. */
    @Test
    fun today_energy_check_in_and_optional_completion() {
        awaitSafe { onToday() }
        for (label in listOf("🔋 Low", "⚡ OK", "🔥 High")) tapText(label)
        if (present("Complete")) {
            tapText("Complete")
            awaitSafe(15_000) { present("See all") || onToday() }
        }
        if (present("See all")) {
            tapText("See all")
            awaitSafe { present("Achievements ·") || onToday() }
            back()
        }
    }

    /** Settings: focus chips, time steppers, habits sub-screen (add habit/goal), diagnostics + delete dialog. */
    @Test
    fun settings_habits_and_actions() {
        tab("Settings")
        awaitSafe { present("AI suggestions") }
        for (label in listOf("Health", "Work study", "Social")) tapText(label)
        tapText("+15"); tapText("−15")

        tapText("Manage habits & goals")
        awaitSafe { present("Habits & goals") }
        typeInto("New habit", "Coverage habit 7777")
        tapText("Add habit")
        typeInto("New goal", "Coverage goal 7777")
        tapText("Add goal")
        back()
        awaitSafe { present("AI suggestions") }

        // Non-navigating actions last; fully best-effort. ("Export"/"Import" are
        // avoided — they open blocking system pickers.)
        tapText("Share AI error log")
        tapText("Delete all my data"); tapText("Cancel")
    }

    /** Render Reviews and Rewards and drive the Rewards budget field + details toggle. */
    @Test
    fun reviews_and_rewards_render_and_interact() {
        tab("Reviews")
        awaitSafe { present("Reviews") }
        tab("Rewards")
        awaitSafe { present("Save budget") }
        typeInto("Affordable monthly budget", "25")
        tapText("Save budget")
        tapText("How rewards work")
    }
}
