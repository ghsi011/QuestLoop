package com.questloop.app.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.questloop.app.data.AiConfig
import com.questloop.app.data.AiProvider
import com.questloop.core.ai.openai.OpenAiOAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the stateless [AiSection], covering both provider branches
 * (OpenRouter key entry and the OpenAI "Sign in with ChatGPT" flow). Run on the
 * JVM via Robolectric so they execute in CI without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class AiSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val connectedOpenAi = AiConfig(
        enabled = true,
        provider = AiProvider.OPENAI,
        openAiTokens = OpenAiOAuth.OpenAiTokens("at", "rt", accountId = "acct"),
        openAiModel = "gpt-5.4",
    )

    private fun render(
        config: AiConfig,
        onSelectProvider: (AiProvider) -> Unit = {},
        onSaveOpenRouter: (Boolean, String, String, Boolean) -> Unit = { _, _, _, _ -> },
        onSaveOpenAi: (Boolean, String, Boolean) -> Unit = { _, _, _ -> },
        onConnectOpenAi: () -> Unit = {},
        onDisconnectOpenAi: () -> Unit = {},
    ) {
        composeRule.setContent {
            AiSection(
                config = config,
                aiBusy = false,
                onSelectProvider = onSelectProvider,
                onSaveOpenRouter = onSaveOpenRouter,
                onSaveOpenAi = onSaveOpenAi,
                onConnectOpenAi = onConnectOpenAi,
                onDisconnectOpenAi = onDisconnectOpenAi,
            )
        }
    }

    @Test
    fun `openrouter branch shows the key field and the provider chips`() {
        var picked: AiProvider? = null
        render(AiConfig(), onSelectProvider = { picked = it })
        composeRule.onNodeWithText("OpenRouter API key").assertIsDisplayed()
        composeRule.onNodeWithText("OpenAI (ChatGPT)").performClick()
        assertEquals(AiProvider.OPENAI, picked)
    }

    @Test
    fun `openrouter save passes the entered values`() {
        var saved: String? = null
        render(AiConfig(apiKey = "sk-1", model = "m"), onSaveOpenRouter = { _, key, model, _ -> saved = "$key/$model" })
        composeRule.onNodeWithText("Save").performClick()
        assertEquals("sk-1/m", saved)
    }

    @Test
    fun `connected openai branch shows the signed-in state and saves the chosen model`() {
        var savedModel: String? = null
        render(connectedOpenAi, onSaveOpenAi = { _, model, _ -> savedModel = model })
        composeRule.onNodeWithText("Signed in to ChatGPT").assertIsDisplayed()
        composeRule.onNodeWithText("gpt-5.4-mini").performClick()
        composeRule.onNodeWithText("Save").performClick()
        assertEquals("gpt-5.4-mini", savedModel)
    }

    @Test
    fun `disconnected openai branch offers sign-in and fires the callback`() {
        var connectTapped = false
        render(connectedOpenAi.copy(openAiTokens = null), onConnectOpenAi = { connectTapped = true })
        composeRule.onNodeWithText("Sign in with ChatGPT").performClick()
        assertTrue(connectTapped)
    }

    @Test
    fun `connected openai branch can disconnect`() {
        var disconnectTapped = false
        render(connectedOpenAi, onDisconnectOpenAi = { disconnectTapped = true })
        composeRule.onNodeWithText("Disconnect").performClick()
        assertTrue(disconnectTapped)
    }
}
