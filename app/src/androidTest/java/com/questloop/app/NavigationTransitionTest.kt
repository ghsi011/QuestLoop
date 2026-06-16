package com.questloop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives real navigation on an emulator: from every screen it presses each
 * available navigation button and asserts the app lands where a user would
 * expect — including that one screen's prior state is honoured on another
 * (tabs restore their stack, a setting survives leaving and returning, a quest
 * added on the Add screen shows up on Quests, a half-built form survives
 * process death).
 *
 * Runs only in the emulator workflow (the `[uitest]` commit marker), like
 * [AppSmokeTest]; it is not compiled or run by the normal CI job.
 *
 * Anchors are chosen to be unambiguous about *which* screen is showing — the
 * bottom-nav labels are always present, so screens are identified by content
 * unique to them (a section header, a primary button, a test tag, or — for
 * Reviews — the section header appearing in addition to the nav label).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class NavigationTransitionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ---- presence helpers -------------------------------------------------

    private fun count(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun present(text: String) = count(text) > 0

    private fun hasTag(tag: String) =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun await(timeoutMs: Long = 8_000, condition: () -> Boolean) =
        composeRule.waitUntil(timeoutMillis = timeoutMs, condition = condition)

    // ---- per-screen "are we here?" predicates -----------------------------

    private fun onToday() = hasTag("today-list")
    private fun onQuests() = present("Browse quest bank")
    private fun onReviews() = count("Reviews") >= 2 // nav label + section header
    private fun onRewards() = present("Save budget")
    private fun onSettings() = present("AI suggestions")
    private fun onQuestBank() = present("Quest bank") // top-bar title (exact text)
    private fun onAchievements() = present("Achievements") // top-bar title
    private fun onHabits() = present("Habits & goals") // top-bar title
    private fun onAdd() = present("New quest") // section header, unique to Add

    // ---- actions ----------------------------------------------------------

    /** Click a bottom-nav tab by its icon contentDescription (unambiguous). */
    private fun tab(label: String) = composeRule.onNodeWithContentDescription(label).performClick()

    private fun back() = composeRule.onNodeWithContentDescription("Back").performClick()

    @Before
    fun reachHome() {
        // First launch shows onboarding; later launches (data persists across
        // tests in one process) go straight to Today.
        await(10_000) { present("Get started") || onToday() }
        if (present("Get started")) composeRule.onNodeWithText("Get started").performClick()
        await { onToday() }
    }

    @Test
    fun bottom_nav_visits_every_tab_and_returns() {
        // Forward through all five tabs.
        tab("Quests"); await { onQuests() }
        tab("Reviews"); await { onReviews() }
        tab("Rewards"); await { onRewards() }
        tab("Settings"); await { onSettings() }
        // …and back to the start, proving every tab is reachable from any other.
        tab("Today"); await { onToday() }
        tab("Settings"); await { onSettings() }
        tab("Quests"); await { onQuests() }
        tab("Today"); await { onToday() }
    }

    @Test
    fun fab_is_present_only_on_today_and_quests_roots() {
        // Today root: FAB present.
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").assertIsDisplayed()

        // Quests root: FAB present.
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithContentDescription("Add quest").assertIsDisplayed()

        // Other tab roots: no FAB.
        tab("Reviews"); await { onReviews() }
        composeRule.onNodeWithContentDescription("Add quest").assertDoesNotExist()
        tab("Settings"); await { onSettings() }
        composeRule.onNodeWithContentDescription("Add quest").assertDoesNotExist()
        tab("Rewards"); await { onRewards() }
        composeRule.onNodeWithContentDescription("Add quest").assertDoesNotExist()
    }

    @Test
    fun every_subscreen_back_button_returns_to_its_owner() {
        // Add (FAB modal) -> back -> Today.
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").performClick()
        await { onAdd() }
        back(); await { onToday() }

        // Achievements (Today "See all") -> back -> Today.
        composeRule.onNodeWithTag("today-list").performScrollToNode(hasText("See all"))
        composeRule.onNodeWithText("See all").performClick()
        await { onAchievements() }
        back(); await { onToday() }

        // Quest bank (Quests "Browse quest bank") -> back -> Quests.
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithText("Browse quest bank").performClick()
        await { onQuestBank() }
        back(); await { onQuests() }

        // Habits (Settings "Manage habits & goals") -> back -> Settings.
        tab("Settings"); await { onSettings() }
        composeRule.onNodeWithText("Manage habits & goals").performScrollTo().performClick()
        await { onHabits() }
        back(); await { onSettings() }
    }

    @Test
    fun retapping_active_tab_pops_its_subscreen_to_root() {
        // Open the Quest Bank under the Quests tab.
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithText("Browse quest bank").performClick()
        await { onQuestBank() }

        // Re-tapping the (already-active) Quests tab returns to the Quests list
        // instead of doing nothing or pushing a duplicate.
        tab("Quests")
        await { onQuests() }
        composeRule.onNodeWithText("Quest bank").assertDoesNotExist()
    }

    @Test
    fun quests_tab_restores_its_open_subscreen_then_pops_to_root() {
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithText("Browse quest bank").performClick()
        await { onQuestBank() }

        // The Quest Bank belongs to the Quests tab. Switching away and back via the
        // tab bar restores that tab's stack — we return to the bank, not some other
        // screen and not a blank Quests list.
        tab("Settings"); await { onSettings() }
        tab("Quests"); await { onQuestBank() }

        // Re-tapping the now-active Quests tab pops the bank, landing on the root.
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithText("Quest bank").assertDoesNotExist()
    }

    @Test
    fun a_setting_changed_on_settings_survives_leaving_and_returning() {
        // Toggle a focus chip on Settings (persisted via the repository).
        tab("Settings"); await { onSettings() }
        val chip = composeRule.onNodeWithText("Health")
        chip.performScrollTo().performClick()
        composeRule.waitForIdle()

        // Leave to another tab and come back: the selection must still be there,
        // i.e. the screen reflects state written before we navigated away.
        tab("Today"); await { onToday() }
        tab("Settings"); await { onSettings() }
        composeRule.onNodeWithText("Health").performScrollTo().assertIsSelected()
    }

    @Test
    fun quest_added_on_add_screen_appears_on_quests_screen() {
        val title = "Transition test quest 4242"

        // Add a uniquely-named quest from the Add modal.
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").performClick()
        await { onAdd() }
        // The title is the first editable field on the form.
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput(title)
        // The button shares its text with the top-bar title; pick the clickable one.
        composeRule.onAllNodesWithText("Add quest").filterToOne(hasClickAction()).performClick()

        // Adding returns to the originating screen (Today)…
        await { onToday() }
        // …and the new quest is now visible in the Quests backlog (prior state of
        // the Add screen reflected on a different screen).
        tab("Quests"); await { onQuests() }
        composeRule.onNodeWithTag("quests-list").performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun half_built_add_form_survives_process_death() {
        // Open Add and choose a non-default difficulty.
        await { onToday() }
        composeRule.onNodeWithContentDescription("Add quest").performClick()
        await { onAdd() }
        composeRule.onNodeWithText("Epic").performScrollTo().performClick()
        composeRule.onNodeWithText("Epic").assertIsSelected()

        // Simulate process death / rotation: the back stack restores the Add
        // screen and the chosen difficulty must come back with it (rememberSaveable).
        composeRule.activityRule.scenario.recreate()
        await { onAdd() }
        composeRule.onNodeWithText("Epic").performScrollTo().assertIsSelected()
    }
}
