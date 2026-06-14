package com.questloop.core.ai

import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiQuestServiceTest {

    private fun service(response: String? = null, fail: Boolean = false) = AiQuestService(
        client = object : LlmClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String): String {
                if (fail) throw RuntimeException("network down")
                return response ?: ""
            }
        },
    )

    @Test
    fun `parses a clean json array into ai-origin quests`() = runTest {
        val svc = service(
            """[
              {"title":"Pay electricity bill","category":"LIFE_ADMIN","difficulty":"EASY","estimatedMinutes":10,"rationale":"Due soon"},
              {"title":"30 min run","category":"HEALTH","difficulty":"MEDIUM"}
            ]""",
        )
        val result = svc.suggest(AiQuestService.Input(todos = listOf("bill", "run")))
        assertTrue(result.fromAi)
        assertEquals(2, result.quests.size)
        assertEquals("Pay electricity bill", result.quests[0].title)
        assertTrue(result.quests.all { it.origin == QuestOrigin.AI_SUGGESTED })
    }

    @Test
    fun `tolerates markdown fences and surrounding prose`() = runTest {
        val svc = service("Sure! Here are some quests:\n```json\n[{\"title\":\"Stretch\",\"category\":\"HEALTH\",\"difficulty\":\"TRIVIAL\"}]\n```")
        val result = svc.suggest(AiQuestService.Input(todos = listOf("stretch")))
        assertTrue(result.fromAi)
        assertEquals(1, result.quests.size)
        assertEquals("Stretch", result.quests.single().title)
    }

    @Test
    fun `falls back to deterministic suggestions on garbage output`() = runTest {
        val svc = service("I cannot help with that.")
        val result = svc.suggest(AiQuestService.Input(todos = listOf("Email landlord")))
        assertFalse(result.fromAi)
        assertEquals("Email landlord", result.quests.first().title) // from FallbackSuggester
    }

    @Test
    fun `falls back when the client throws`() = runTest {
        val svc = service(fail = true)
        val result = svc.suggest(AiQuestService.Input(todos = listOf("Call mom")))
        assertFalse(result.fromAi)
        assertTrue(result.quests.isNotEmpty())
    }

    @Test
    fun `unsafe model suggestions are filtered by the guardrails`() = runTest {
        val svc = service(
            """[
              {"title":"Invest in crypto for guaranteed return","category":"LIFE_ADMIN","difficulty":"EASY"},
              {"title":"Tidy the kitchen","category":"CHORES","difficulty":"EASY"}
            ]""",
        )
        val result = svc.suggest(AiQuestService.Input(todos = listOf("tidy")))
        assertTrue(result.fromAi)
        assertTrue(result.quests.none { it.title.contains("crypto", ignoreCase = true) })
        assertTrue(result.quests.any { it.title == "Tidy the kitchen" })
    }

    @Test
    fun `empty response with no todos still yields a safe fallback`() = runTest {
        val svc = service("[]")
        val result = svc.suggest(AiQuestService.Input(focusAreas = listOf(QuestCategory.HEALTH)))
        assertFalse(result.fromAi)
        assertEquals(1, result.quests.size)
        assertEquals(QuestCategory.HEALTH, result.quests.single().category)
    }
}
