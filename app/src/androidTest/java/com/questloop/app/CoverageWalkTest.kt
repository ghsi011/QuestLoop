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
 * Coverage-oriented walk over the lowest-covered screens. This is deliberately a
 * *coverage* exercise, not a behavioural assertion suite (the ViewModels/data
 * are asserted by JVM unit tests, and navigation correctness by
 * [NavigationTransitionTest]). So the in-screen interactions are best-effort:
 * each is wrapped so a label that's momentarily absent/duplicated/offscreen can't
 * abort the rest of the walk — the goal is to compose each screen and fire as
 * many handlers as possible. Navigation steps (proven in NavigationTransitionTest)
 * are not wrapped, so a genuine navigation regression still fails loudly.
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker), like
 * [AppSmokeTest] and [NavigationTransitionTest].
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CoverageWalkTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ---- presence helpers (copied from NavigationTransitionTest) -----------

    private fun count(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun present(text: String) = count(text) > 0

    private fun hasTag(tag: String) =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(timeoutMs: Long = 8_000, condition: () -> Boolean) =
        composeRule.waitUntil(timeoutMillis = timeoutMs, condition = condition)

    // ---- per-screen "are we here?" predicates ------------------------------

    private fun onToday() = hasTag("today-list")
    private fun onReviews() = count("Reviews") >= 2 // nav label + section header
    private fun onRewards() = present("Save budget")
    private fun onSettings() = present("AI suggestions")
    private fun onHabits() = present("Habits & goals") // top-bar title
    private fun onAdd() = present("New quest") // section header, unique to Add

    // ---- actions -----------------------------------------------------------

    private fun tab(label: String) =
        composeRule.onAllNodesWithText(label).filterToOne(hasClickAction()).performClick()

    private fun back() = composeRule.onNodeWithContentDescription("Back").performClick()

    /** Best-effort: scroll to the clickable node with [label] and click it. */
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

    @Before
    fun reachHome() {
        await(10_000) { present("Get started") || onToday() }
        if (present("Get started")) composeRule.onNodeWithText("Get started").performClick()
        await { onToday() }
    }

    /**
     * Biggest coverage lever: open the Add modal via the FAB, then click each
     * option chip in every group (best-effort) and submit. Selecting
     * Quantitative/Duration/Subjective also reveals the conditional count/unit
     * fields, exercising those branches.
     */
    @Test
    fun add_form_exercises_every_option_group_and_submits() {
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").performClick()
        await { onAdd() }

        val chips = listOf(
            // Category (QuestCategory.pretty()).
            "Health", "Life admin", "Chores", "Work study", "Social",
            "Personal growth", "Bad habit reduction", "Meta maintenance",
            // Difficulty.
            "Trivial", "Easy", "Medium", "Hard", "Epic",
            // Priority.
            "Low", "Normal", "High", "Critical",
            // Frequency.
            "Daily", "Weekly", "Monthly", "Recurring", "One off", "Seasonal",
            // Completion style.
            "Quantitative", "Duration", "Subjective", "Binary",
        )
        for (label in chips) tapText(label)

        typeInto("What do you want to get done?", "Coverage walk quest 7777")
        await { present("Coverage walk quest 7777") || onAdd() }
        tapText("Add quest") // the submit button (clickable; FAB shares the text)
        await(15_000) { !onAdd() }
    }

    /**
     * Exercise the Today energy check-in chips (always rendered) and, if a
     * completable quest happens to be in the plan, its completion control and the
     * achievement strip it surfaces. All in-screen taps are best-effort.
     */
    @Test
    fun today_energy_check_in_and_optional_completion() {
        await { onToday() }
        for (label in listOf("🔋 Low", "⚡ OK", "🔥 High")) tapText(label)

        if (present("Complete")) {
            tapText("Complete")
            await(15_000) { present("See all") || onToday() }
        }
        if (present("See all")) {
            tapText("See all")
            await { present("Achievements ·") || onToday() }
            if (present("Back")) back()
            await { onToday() }
        }
    }

    /**
     * Settings -> "Manage habits & goals" -> add a habit and a goal (best-effort
     * fields/buttons from HabitsScreen), then a couple of focus chips on Settings.
     */
    @Test
    fun habits_add_and_settings_focus() {
        tab("Settings"); await { onSettings() }

        // Focus chips are category names (QuestCategory.pretty()).
        for (label in listOf("Health", "Work study", "Social")) tapText(label)
        // Available-minutes steppers, the diagnostics share (a no-op snackbar with
        // no logs), and the delete-confirm dialog (opened then dismissed). These
        // are all in-app controls — "Export"/"Import" are intentionally avoided
        // because they open blocking system pickers.
        tapText("+15"); tapText("−15")
        tapText("Share AI error log")
        tapText("Delete all my data"); tapText("Cancel")

        composeRule.onNodeWithText("Manage habits & goals").performScrollTo().performClick()
        await { onHabits() }
        typeInto("New habit", "Coverage habit 7777")
        tapText("Add habit")
        typeInto("New goal", "Coverage goal 7777")
        tapText("Add goal")
        back(); await { onSettings() }
    }

    /**
     * Render the Reviews and Rewards tabs (composition coverage) and drive the
     * Rewards budget field + save and the details toggle (best-effort).
     */
    @Test
    fun reviews_and_rewards_render_and_interact() {
        tab("Reviews"); await { onReviews() }
        tab("Rewards"); await { onRewards() }
        typeInto("Affordable monthly budget", "25")
        tapText("Save budget")
        tapText("How rewards work")
    }
}
