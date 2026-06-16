package com.questloop.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    }
}
