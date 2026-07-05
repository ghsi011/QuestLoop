package com.questloop.core.ai

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiQuestServiceTest {

    private fun service(response: String? = null, fail: Boolean = false, failWith: Throwable? = null, cancel: Boolean = false) = AiQuestService(
        client = object : LlmClient {
            override suspend fun complete(systemPrompt: String, userPrompt: String): String {
                if (cancel) throw CancellationException("cancelled")
                failWith?.let { throw it }
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
    fun `decomposes a goal into reviewable quests`() = runTest {
        val svc = service(
            """[
              {"title":"Sign up for a 10k race","category":"HEALTH","difficulty":"EASY"},
              {"title":"Run 2k three times a week","category":"HEALTH","difficulty":"MEDIUM","frequency":"WEEKLY"}
            ]""",
        )
        val result = svc.decomposeGoal("Run a 10k")
        assertTrue(result.fromAi)
        assertEquals(2, result.quests.size)
        assertEquals("Sign up for a 10k race", result.quests[0].title)
    }

    @Test
    fun `goal decomposition falls back when the model fails`() = runTest {
        val result = service(fail = true).decomposeGoal("Run a 10k")
        assertFalse(result.fromAi)
        assertTrue(result.quests.isNotEmpty())
        assertNotNull(result.error)
    }

    @Test
    fun `blank goal yields nothing to decompose`() = runTest {
        val result = service("[]").decomposeGoal("   ")
        assertTrue(result.quests.isEmpty())
        assertFalse(result.fromAi)
    }

    @Test
    fun `goal decomposition dedups against existing quests`() = runTest {
        val svc = service(
            """[
              {"title":"Sign up for a 10k race","category":"HEALTH","difficulty":"EASY"},
              {"title":"Buy running shoes","category":"HEALTH","difficulty":"EASY"}
            ]""",
        )
        val existing = listOf(
            Quest("e", "Buy running shoes", QuestCategory.HEALTH, QuestFrequency.ONE_OFF, Difficulty.EASY),
        )
        val result = svc.decomposeGoal("Run a 10k", existing)
        assertEquals(1, result.quests.size)
        assertEquals("Sign up for a 10k race", result.quests.single().title)
    }

    @Test
    fun `goal decomposition clamps inflated difficulty for a short step`() = runTest {
        val svc = service("""[{"title":"Quick warm-up","category":"HEALTH","difficulty":"EPIC","estimatedMinutes":2}]""")
        val result = svc.decomposeGoal("Get fit")
        assertEquals(Difficulty.TRIVIAL, result.quests.single().difficulty)
    }

    @Test
    fun `empty model array falls back to one starter step`() = runTest {
        val result = service("[]").decomposeGoal("Learn guitar")
        assertFalse(result.fromAi)
        assertEquals(1, result.quests.size)
        assertNotNull(result.error)
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
    fun `a transport failure is surfaced as an error`() = runTest {
        val svc = service(fail = true)
        val result = svc.suggest(AiQuestService.Input(todos = listOf("Call mom")))
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("network down"))
    }

    @Test
    fun `offline failure shows plain copy and keeps the raw detail for the log`() = runTest {
        val svc = service(
            failWith = UnknownHostException("Unable to resolve host \"openrouter.ai\": No address associated with hostname"),
        )
        val result = svc.suggest(AiQuestService.Input(todos = listOf("Call mom")))
        assertFalse(result.fromAi)
        assertEquals("Couldn't reach the AI. Check your connection and try again.", result.error)
        assertTrue(result.errorDetail!!.contains("openrouter.ai"))
        assertTrue(result.quests.isNotEmpty())
    }

    @Test
    fun `timeout failure shows the same plain copy in suggest, decompose and refine`() = runTest {
        val plainCopy = "Couldn't reach the AI. Check your connection and try again."
        val svc = service(failWith = SocketTimeoutException("timeout"))
        assertEquals(plainCopy, svc.suggest(AiQuestService.Input(todos = listOf("x"))).error)
        assertEquals(plainCopy, svc.decomposeGoal("Run a 10k").error)
        val original = Quest("x", "Call mum", QuestCategory.SOCIAL, QuestFrequency.ONE_OFF, Difficulty.EASY)
        val refined = svc.refine(original, "make it harder")
        assertEquals(plainCopy, refined.error)
        assertNull(refined.quest)
    }

    @Test
    fun `curated provider errors keep surfacing verbatim`() = runTest {
        val svc = service(failWith = IOException("OpenRouter request failed (404) for model \"m\": No endpoints found"))
        val result = svc.suggest(AiQuestService.Input(todos = listOf("x")))
        assertTrue(result.error!!.contains("No endpoints found"))
        assertNull(result.errorDetail)
    }

    @Test
    fun `a failure with no message still reports something human`() = runTest {
        val result = service(failWith = IOException()).suggest(AiQuestService.Input(todos = listOf("x")))
        assertEquals("AI request failed: couldn't reach the model", result.error)
    }

    @Test
    fun `cancellation is rethrown instead of becoming a fallback`() = runTest {
        val svc = service(cancel = true)
        assertFailsWith<CancellationException> { svc.suggest(AiQuestService.Input(todos = listOf("Call mom"))) }
        assertFailsWith<CancellationException> { svc.decomposeGoal("Run a 10k") }
        val original = Quest(
            id = "x",
            title = "Call mum",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
        )
        assertFailsWith<CancellationException> { svc.refine(original, "make it harder") }
    }

    @Test
    fun `unparseable output is surfaced as an error`() = runTest {
        val svc = service("I cannot help with that.")
        val result = svc.suggest(AiQuestService.Input(todos = listOf("Email landlord")))
        assertNotNull(result.error)
    }

    @Test
    fun `a successful suggestion has no error`() = runTest {
        val svc = service("""[{"title":"Stretch","category":"HEALTH","difficulty":"TRIVIAL"}]""")
        val result = svc.suggest(AiQuestService.Input(todos = listOf("stretch")))
        assertTrue(result.fromAi)
        assertNull(result.error)
    }

    @Test
    fun `parses frequency, completion style, priority and target`() = runTest {
        val svc = service(
            """[{"title":"Drink water","category":"HEALTH","difficulty":"EASY","priority":"HIGH",
               "frequency":"DAILY","completionStyle":"QUANTITATIVE","targetCount":8,"unit":"glasses"}]""",
        )
        val q = svc.suggest(AiQuestService.Input(todos = listOf("water"))).quests.single()
        assertEquals(QuestFrequency.DAILY, q.frequency)
        assertEquals(CompletionStyle.QUANTITATIVE, q.completionStyle)
        assertEquals(8, q.targetCount)
        assertEquals("glasses", q.unit)
    }

    @Test
    fun `refine revises a quest and keeps its id`() = runTest {
        val svc = service(
            """[{"title":"Call mum","category":"SOCIAL","difficulty":"EASY","frequency":"WEEKLY"}]""",
        )
        val original = Quest(
            id = "x",
            title = "Call mum",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
        )
        val result = svc.refine(original, "make it a weekly habit")
        assertEquals("x", result.quest?.id)
        assertEquals(QuestFrequency.WEEKLY, result.quest?.frequency)
        assertNull(result.error)
    }

    @Test
    fun `refine surfaces an error when the model fails`() = runTest {
        val svc = service(fail = true)
        val original = Quest(
            id = "x",
            title = "Call mum",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
        )
        val result = svc.refine(original, "make it harder")
        assertNull(result.quest)
        assertNotNull(result.error)
    }

    @Test
    fun `refine with a blank instruction returns the original unchanged`() = runTest {
        val svc = service("[]")
        val original = Quest(
            id = "x",
            title = "Call mum",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
        )
        val result = svc.refine(original, "   ")
        assertEquals(original, result.quest)
        assertNull(result.error)
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
