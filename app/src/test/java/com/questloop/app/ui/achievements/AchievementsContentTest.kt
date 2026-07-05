package com.questloop.app.ui.achievements

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.questloop.app.data.QuestRepository
import com.questloop.core.reward.Achievement
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the stateless [AchievementsContent] (the Achievements screen
 * body). Run on the JVM via Robolectric so they execute without an emulator. The
 * unlock logic itself is covered by [AchievementsViewModelTest]; here we verify each
 * rendering branch — loading, error (+retry), and the unlocked/locked card list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AchievementsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun status(id: String, title: String, desc: String, unlocked: Boolean) =
        QuestRepository.AchievementStatus(
            Achievement(id = id, title = title, description = desc, predicate = { false }),
            unlocked = unlocked,
        )

    @Test
    fun `loaded state renders unlocked and locked achievements with the count header`() {
        val state = AchievementsUiState(
            loading = false,
            items = listOf(
                status("first", "First quest", "Complete your first quest", unlocked = true),
                status("ten", "Ten quests", "Reach ten completions", unlocked = false),
            ),
            unlockedCount = 1,
        )
        composeRule.setContent { AchievementsContent(state, onRetry = {}) }

        composeRule.onNodeWithText("Achievements · 1/2").assertIsDisplayed()
        // Unlocked shows a trophy, locked shows a padlock (two spaces, per the screen).
        composeRule.onNodeWithText("🏆  First quest").assertIsDisplayed()
        composeRule.onNodeWithText("🔒  Ten quests").assertIsDisplayed()
        composeRule.onNodeWithText("Complete your first quest").assertIsDisplayed()
        composeRule.onNodeWithText("Reach ten completions").assertIsDisplayed()
    }

    @Test
    fun `error state offers a retry that fires the callback`() {
        var retried = false
        composeRule.setContent {
            AchievementsContent(
                AchievementsUiState(loading = false, error = "Something went wrong."),
                onRetry = { retried = true },
            )
        }
        composeRule.onNodeWithText("Something went wrong.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertTrue("the retry button must invoke onRetry", retried)
    }

    @Test
    fun `loading state shows a spinner instead of the achievement list`() {
        composeRule.setContent { AchievementsContent(AchievementsUiState(loading = true), onRetry = {}) }
        // The spinner replaces the list: neither the count header nor any card is shown.
        composeRule.onAllNodesWithText("Achievements", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Try again").assertCountEquals(0)
    }
}
