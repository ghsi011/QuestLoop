package com.questloop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
 * Coverage-oriented walk over the lowest-covered screens. Each @Test is
 * independent and starts from a clean Today via [reachHome], so one failure
 * doesn't mask the others. The harness (rule, presence helpers, tab/back
 * actions, onboarding handling) is copied verbatim from
 * [NavigationTransitionTest]; selectors are taken from the screen sources
 * (AddQuestScreen, TodayScreen, QuestControls, HabitsScreen, AchievementsScreen,
 * ReviewScreen, RewardsScreen).
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker), like
 * [AppSmokeTest] and [NavigationTransitionTest]; it is not compiled or run by
 * the normal CI job.
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
    private fun onQuests() = present("Browse quest bank")
    private fun onReviews() = count("Reviews") >= 2 // nav label + section header
    private fun onRewards() = present("Save budget")
    private fun onSettings() = present("AI suggestions")
    private fun onHabits() = present("Habits & goals") // top-bar title
    private fun onAdd() = present("New quest") // section header, unique to Add
    private fun onAchievements() = present("Achievements ·") // section header

    // ---- actions (copied from NavigationTransitionTest) --------------------

    private fun tab(label: String) =
        composeRule.onAllNodesWithText(label).filterToOne(hasClickAction()).performClick()

    private fun back() = composeRule.onNodeWithContentDescription("Back").performClick()

    @Before
    fun reachHome() {
        await(10_000) { present("Get started") || onToday() }
        if (present("Get started")) composeRule.onNodeWithText("Get started").performClick()
        await { onToday() }
    }

    /**
     * Biggest coverage lever: open the Add modal via the FAB, then scroll to and
     * click each option chip in every group. The chip labels come from
     * AddQuestScreen.ChipGroup, whose prettyOf() lowercases the enum name and
     * capitalises the first char (e.g. Difficulty.EPIC -> "Epic"); QuestCategory
     * uses the same scheme via QuestCategory.pretty(). Finally type a title and
     * submit, exercising every option handler plus addQuest().
     */
    @Test
    fun add_form_exercises_every_option_group_and_submits() {
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").performClick()
        await { onAdd() }

        // Category (QuestCategory.entries, pretty()): exact labels.
        for (label in listOf(
            "Health", "Life admin", "Chores", "Work study", "Social",
            "Personal growth", "Bad habit reduction", "Meta maintenance",
        )) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }

        // Difficulty (Difficulty.entries, prettyOf).
        for (label in listOf("Trivial", "Easy", "Medium", "Hard", "Epic")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }

        // Priority (Priority.entries, prettyOf).
        for (label in listOf("Low", "Normal", "High", "Critical")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }

        // Frequency (QuestFrequency.entries, prettyOf).
        for (label in listOf("Daily", "Weekly", "Monthly", "Recurring", "One off", "Seasonal")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }

        // Completion style (CompletionStyle.entries, prettyOf). Picking
        // Quantitative/Duration/Subjective reveals/hides the count+unit fields,
        // so this also exercises those conditional branches.
        for (label in listOf("Quantitative", "Duration", "Subjective", "Binary")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
        }

        // Title field (located by its label) then submit.
        val title = "Coverage walk quest 7777"
        composeRule.onNodeWithText("What do you want to get done?")
            .performScrollTo().performTextInput(title)
        composeRule.waitForIdle()
        await { present(title) }
        // The button text matches the top-bar title; pick the clickable one and
        // scroll to it (it sits below the long form's fold).
        composeRule.onAllNodesWithText("Add quest").filterToOne(hasClickAction())
            .performScrollTo().performClick()

        // Submitting closes the modal (re-loading Today's plan can be slow on a
        // cold emulator, so wait generously).
        await(15_000) { !onAdd() }
    }

    /**
     * Exercise the Today energy check-in chips (always rendered) and, if a
     * completable quest is present, its completion control. The energy labels are
     * from TodayScreen.EnergyCheckInRow; the "Complete" button is the default
     * (BINARY) control from QuestCompletionControls. After completing a quest the
     * "first steps" achievement is earned, surfacing the achievement strip.
     */
    @Test
    fun today_energy_check_in_and_quest_completion() {
        await { onToday() }

        // Energy check-in chips are always shown on Today.
        for (label in listOf("🔋 Low", "⚡ OK", "🔥 High")) {
            composeRule.onNodeWithText(label).performScrollTo().performClick()
            composeRule.waitForIdle()
        }

        // Complete a quest if one with the default binary control is present.
        // (Onboarding guide rows show CTA buttons instead, and which real quests
        // are planned is data-dependent, so this is guarded.)
        if (present("Complete")) {
            composeRule.onAllNodesWithText("Complete").filterToOne(hasClickAction())
                .performScrollTo().performClick()
            composeRule.waitForIdle()
            // Earning "first steps" surfaces the achievement strip ("See all").
            await(15_000) { present("See all") || onToday() }
        }
    }

    /**
     * After completing a quest (earning "first steps"), the Today achievement
     * strip appears; tapping it opens the Achievements screen. The strip's
     * "See all" chip and the screen's "Achievements ·" header are both from
     * source (TodayScreen.AchievementStrip / AchievementsScreen). Guarded on the
     * strip being present, since it only appears once an achievement is earned.
     */
    @Test
    fun completing_a_quest_opens_achievements_strip() {
        await { onToday() }

        if (present("Complete")) {
            composeRule.onAllNodesWithText("Complete").filterToOne(hasClickAction())
                .performScrollTo().performClick()
            composeRule.waitForIdle()
            await(15_000) { present("See all") || onToday() }
        }

        if (present("See all")) {
            composeRule.onAllNodesWithText("See all").filterToOne(hasClickAction())
                .performScrollTo().performClick()
            await { onAchievements() }
            composeRule.onNodeWithText("Achievements ·").assertIsDisplayed()
            back(); await { onToday() }
        }
    }

    /**
     * Settings -> "Manage habits & goals" -> add a habit and a goal. The nav
     * link text is from SettingsScreen (also used by NavigationTransitionTest);
     * the field labels ("New habit", "New goal") and button texts ("Add habit",
     * "Add goal") are from HabitsScreen's AddHabitForm / AddGoalForm.
     */
    @Test
    fun habits_screen_adds_a_habit_and_a_goal() {
        tab("Settings"); await { onSettings() }
        composeRule.onNodeWithText("Manage habits & goals").performScrollTo().performClick()
        await { onHabits() }

        // Add a habit.
        composeRule.onNodeWithText("New habit").performScrollTo().performTextInput("Coverage habit 7777")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add habit").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Add a goal.
        composeRule.onNodeWithText("New goal").performScrollTo().performTextInput("Coverage goal 7777")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Add goal").performScrollTo().performClick()
        composeRule.waitForIdle()

        // Both should now appear as rows under their sections.
        composeRule.onNodeWithText("Coverage habit 7777").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Coverage goal 7777").performScrollTo().assertIsDisplayed()
    }

    /**
     * Render the Reviews and Rewards tabs and assert a unique anchor on each
     * (coverage from composition). Reviews is identified by its section header
     * appearing in addition to the nav label (onReviews()); Rewards by its
     * "Save budget" primary button. Also drive the Rewards budget field + save
     * and the "How rewards work" toggle, which are cheap extra branches.
     */
    @Test
    fun reviews_and_rewards_render_with_anchors() {
        tab("Reviews"); await { onReviews() }
        composeRule.onAllNodesWithText("Reviews").filterToOne(hasClickAction()).assertIsDisplayed()

        tab("Rewards"); await { onRewards() }
        composeRule.onNodeWithText("Save budget").assertIsDisplayed()

        // Cheap extra coverage: set a budget and toggle the details section.
        composeRule.onNodeWithText("Affordable monthly budget").performScrollTo().performTextInput("25")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save budget").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("How rewards work").performScrollTo().performClick()
        composeRule.waitForIdle()
    }
}
