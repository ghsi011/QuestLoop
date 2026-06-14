package com.questloop.core.ai

import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestOrigin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val quests = svc.suggest(AiQuestService.Input(todos = listOf("bill", "run")))
        assertEquals(2, quests.size)
        assertEquals("Pay electricity bill", quests[0].title)
        assertTrue(quests.all { it.origin == QuestOrigin.AI_SUGGESTED })
    }

    @Test
    fun `tolerates markdown fences and surrounding prose`() = runTest {
        val svc = service("Sure! Here are some quests:\n```json\n[{\"title\":\"Stretch\",\"category\":\"HEALTH\",\"difficulty\":\"TRIVIAL\"}]\n```")
        val quests = svc.suggest(AiQuestService.Input(todos = listOf("stretch")))
        assertEquals(1, quests.size)
        assertEquals("Stretch", quests.single().title)
    }

    @Test
    fun `falls back to deterministic suggestions on garbage output`() = runTest {
        val svc = service("I cannot help with that.")
        val quests = svc.suggest(AiQuestService.Input(todos = listOf("Email landlord")))
        assertTrue(quests.isNotEmpty())
        assertEquals("Email landlord", quests.first().title) // from FallbackSuggester
    }

    @Test
    fun `falls back when the client throws`() = runTest {
        val svc = service(fail = true)
        val quests = svc.suggest(AiQuestService.Input(todos = listOf("Call mom")))
        assertTrue(quests.isNotEmpty())
    }

    @Test
    fun `unsafe model suggestions are filtered by the guardrails`() = runTest {
        val svc = service(
            """[
              {"title":"Invest in crypto for guaranteed return","category":"LIFE_ADMIN","difficulty":"EASY"},
              {"title":"Tidy the kitchen","category":"CHORES","difficulty":"EASY"}
            ]""",
        )
        val quests = svc.suggest(AiQuestService.Input(todos = listOf("tidy")))
        assertTrue(quests.none { it.title.contains("crypto", ignoreCase = true) })
        assertTrue(quests.any { it.title == "Tidy the kitchen" })
    }

    @Test
    fun `empty response with no todos still yields a safe fallback`() = runTest {
        val svc = service("[]")
        val quests = svc.suggest(AiQuestService.Input(focusAreas = listOf(QuestCategory.HEALTH)))
        assertEquals(1, quests.size)
        assertEquals(QuestCategory.HEALTH, quests.single().category)
    }
}
