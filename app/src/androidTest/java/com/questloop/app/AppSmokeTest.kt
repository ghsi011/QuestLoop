package com.questloop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.takeScreenshot
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end smoke test on a real Android runtime (emulator). It walks the main
 * screens and writes a screenshot of each to Test Storage, which CI collects as
 * artifacts (build/outputs/connected_android_test_additional_output). This is the
 * UI feedback loop for bigger UI features — run it on demand with the `[uitest]`
 * commit marker or the manual workflow.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun present(text: String) =
        composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Hard assertion: fail (timeout) if [text] never appears, so broken nav fails the test. */
    private fun awaitText(text: String) =
        composeRule.waitUntil(timeoutMillis = 8_000) { present(text) }

    private fun shoot(name: String) {
        composeRule.waitForIdle()
        takeScreenshot().writeToTestStorage(name)
    }

    /**
     * Best-effort: click the first node labelled [text] if present (indexing the
     * first match tolerates several — e.g. multiple "Complete" buttons — where
     * filterToOne would throw). No-op if absent; the scroll/click are swallowed so a
     * momentarily off-screen or recomposed node can't fail the walk.
     */
    private fun tapIfPresent(text: String) {
        if (!present(text)) return
        runCatching { composeRule.onAllNodesWithText(text)[0].performScrollTo() }
        runCatching {
            composeRule.onAllNodesWithText(text)[0].performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun walks_main_screens_and_captures_screenshots() {
        // First launch may show onboarding; wait for either it or the Today tab.
        composeRule.waitUntil(timeoutMillis = 10_000) { present("Get started") || present("Today") }
        if (present("Get started")) {
            shoot("01-onboarding")
            composeRule.onNodeWithText("Get started").performClick()
        }

        awaitText("Today")
        shoot("02-today")

        // Seed a completion so the Completed-history screen (walked below) has a real
        // row to render — exercising the cards + row actions, not just the empty state.
        tapIfPresent("Complete")

        composeRule.onNodeWithText("Quests").performClick()
        awaitText("Browse quest bank")
        shoot("03-quests")

        composeRule.onNodeWithText("Browse quest bank").performClick()
        awaitText("Daily")
        shoot("04-quest-bank")

        composeRule.onNodeWithText("Settings").performClick()
        awaitText("AI suggestions")
        shoot("05-settings")

        composeRule.onNodeWithText("Rewards").performClick()
        awaitText("Save budget")
        shoot("06-rewards")

        // Reviews → the Completed-history sub-screen. Nav is hard-asserted (a broken
        // "Completed quests →" fails the test); the row actions are best-effort so the
        // walk still covers the screen when no completion was seeded (empty state).
        composeRule.onNodeWithText("Reviews").performClick()
        awaitText("Completed quests →")
        shoot("07-reviews")
        composeRule.onNodeWithText("Completed quests →").performClick()
        awaitText("Completed")
        // Cycle a filter, then drive edit (open dialog → re-score → save), re-add, undo.
        tapIfPresent("All time")
        tapIfPresent("Edit")
        if (present("Edit quest")) {
            tapIfPresent("Hard")
            tapIfPresent("Save")
        }
        tapIfPresent("Re-add")
        tapIfPresent("Undo")
        shoot("08-completed")
    }
}
