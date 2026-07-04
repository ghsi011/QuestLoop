package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.ai.openai.OpenAiOAuth
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the OpenAI (ChatGPT OAuth) suggestion path end-to-end through the
 * repository: provider selection, lazy token refresh, and the Codex Responses
 * call against a local [MockWebServer]. Uses a fake [OpenAiAuth] so no real
 * browser/socket handshake is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryOpenAiTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var server: MockWebServer
    private val prefs = FakePrefs()
    private val auth = FakeAuth()
    private lateinit var repo: QuestRepository

    private class FakeAuth : OpenAiAuth {
        var refreshCount = 0
        var refreshed = OpenAiOAuth.OpenAiTokens("fresh-at", "fresh-rt", accountId = "acct", expiresAtEpochSec = Long.MAX_VALUE)

        /** When set, [refresh] signals [refreshStarted] then suspends until it completes. */
        var refreshGate: CompletableDeferred<Unit>? = null
        val refreshStarted = CompletableDeferred<Unit>()

        override suspend fun signIn(
            timeoutMs: Long,
            onTokens: suspend (OpenAiOAuth.OpenAiTokens) -> Unit,
            openUrl: (String) -> Unit,
        ): Result<OpenAiOAuth.OpenAiTokens> {
            onTokens(refreshed)
            return Result.success(refreshed)
        }
        override suspend fun refresh(tokens: OpenAiOAuth.OpenAiTokens): Result<OpenAiOAuth.OpenAiTokens> {
            refreshCount++
            refreshGate?.let { gate ->
                refreshStarted.complete(Unit)
                gate.await()
            }
            return Result.success(refreshed)
        }
    }

    private class RecordingDiagnostics : AiDiagnostics {
        private val entries = mutableListOf<String>()
        override fun record(model: String, message: String) { entries += "[$model] $message" }
        override fun dump(): String = entries.joinToString("\n")
        override fun clear() = entries.clear()
    }

    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile())
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {}
        override suspend fun setMaxDaily(value: Int) {}
        override suspend fun setAvailableMinutes(value: Int) {}
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {}
        override suspend fun setStreakGraceDays(value: Int) {}
        override suspend fun setSensitiveOptIn(value: Boolean) {}
        override suspend fun setHabits(habits: List<Habit>) {}
        override suspend fun setBadHabits(badHabits: List<BadHabit>) {}
        override suspend fun setGoals(goals: List<Goal>) {}
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {}
        override suspend fun getCheckIn(): EnergyCheckIn? = null
        var ai = AiConfig()
        override suspend fun getAiConfig(): AiConfig = ai
        override suspend fun setAiConfig(config: AiConfig) { ai = config }
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = ReminderConfig()
        override suspend fun setReminderConfig(config: ReminderConfig) {}
        override suspend fun clear() { ai = AiConfig() }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        server = MockWebServer()
        server.start()
        repo = QuestRepository(
            db.questDao(),
            db.completionDao(),
            prefs,
            openAiAuth = auth,
            openAiResponsesEndpoint = server.url("/codex/responses").toString(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun enqueueQuestSse() {
        val arrayJson = """[{\"title\":\"Pay rent\",\"category\":\"LIFE_ADMIN\",\"difficulty\":\"EASY\"}]"""
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""data: {"type":"response.output_text.delta","delta":"$arrayJson"}"""),
        )
    }

    @Test
    fun `OpenAI provider suggests quests with a still-valid token (no refresh)`() = runTest {
        prefs.ai = AiConfig(
            enabled = true,
            provider = AiProvider.OPENAI,
            openAiTokens = OpenAiOAuth.OpenAiTokens("at", "rt", accountId = "acct", expiresAtEpochSec = Long.MAX_VALUE),
            openAiModel = "gpt-5.4",
        )
        enqueueQuestSse()

        val result = repo.suggestQuests(listOf("rent"))

        assertTrue(result.fromAi)
        assertEquals("Pay rent", result.quests.single().title)
        assertNull(result.error)
        assertEquals("no refresh when the token is fresh", 0, auth.refreshCount)
        // The request reached the Codex endpoint with the bearer token.
        assertEquals("Bearer at", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `OpenAI provider refreshes and persists an expired token before calling`() = runTest {
        prefs.ai = expiredTokenConfig()
        enqueueQuestSse()

        val result = repo.suggestQuests(listOf("rent"))

        assertTrue(result.fromAi)
        assertEquals("refreshed exactly once", 1, auth.refreshCount)
        // The refreshed tokens are persisted and the call used the new access token.
        assertEquals("fresh-at", prefs.ai.openAiTokens?.accessToken)
        assertEquals("Bearer fresh-at", server.takeRequest().getHeader("Authorization"))
    }

    /** An OpenAI config whose access token is already expired, forcing a refresh. */
    private fun expiredTokenConfig(apiKey: String = "") = AiConfig(
        enabled = true,
        provider = AiProvider.OPENAI,
        apiKey = apiKey,
        openAiTokens = OpenAiOAuth.OpenAiTokens(
            "stale",
            "rt",
            accountId = "acct",
            expiresAtEpochSec = System.currentTimeMillis() / 1000 - 100,
        ),
        openAiModel = "gpt-5.4",
    )

    @Test
    fun `disconnect during an in-flight refresh is not undone by the refresh persist`() = runTest {
        prefs.ai = expiredTokenConfig()
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = gate
        enqueueQuestSse()

        val call = launch { repo.suggestQuests(listOf("rent")) }
        auth.refreshStarted.await()
        // Sign out while the refresh is mid-flight: it must serialise behind the
        // refresh's persist so the cleared tokens stay cleared.
        val disconnect = launch { repo.disconnectOpenAi() }
        gate.complete(Unit)
        call.join()
        disconnect.join()

        assertNull("sign-out must win over the in-flight refresh", prefs.ai.openAiTokens)
    }

    @Test
    fun `delete-all during an in-flight refresh is not undone by the refresh persist`() = runTest {
        prefs.ai = expiredTokenConfig(apiKey = "or-key")
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = gate
        enqueueQuestSse()

        val call = launch { repo.suggestQuests(listOf("rent")) }
        auth.refreshStarted.await()
        val wipe = launch { repo.deleteAllData() }
        gate.complete(Unit)
        call.join()
        wipe.join()

        // SPEC §9: after "delete all my data", no credential survives the race.
        assertNull(prefs.ai.openAiTokens)
        assertEquals("", prefs.ai.apiKey)
    }

    @Test
    fun `refresh aborts rather than re-persisting credentials cleared mid-flight`() = runTest {
        prefs.ai = expiredTokenConfig()
        val gate = CompletableDeferred<Unit>()
        auth.refreshGate = gate
        enqueueQuestSse()

        val call = async { repo.suggestQuests(listOf("rent")) }
        auth.refreshStarted.await()
        // A writer that bypasses the auth mutex clears the config mid-refresh:
        // defence in depth for the disconnect/delete-all class of races.
        prefs.ai = AiConfig()
        gate.complete(Unit)
        val result = call.await()

        assertNull("cleared tokens must not be re-persisted", prefs.ai.openAiTokens)
        assertFalse("the aborted call must not pose as AI output", result.fromAi)
        assertTrue("the abort surfaces an error to the caller", result.error != null)
    }
    @Test
    fun `a connection failure shows plain copy and logs the raw transport detail`() = runTest {
        prefs.ai = AiConfig(
            enabled = true,
            provider = AiProvider.OPENAI,
            openAiTokens = OpenAiOAuth.OpenAiTokens("at", "rt", accountId = "acct", expiresAtEpochSec = Long.MAX_VALUE),
            openAiModel = "gpt-5.4",
        )
        val diag = RecordingDiagnostics()
        // Nothing listens on port 1, so the call dies with a raw ConnectException.
        val offline = QuestRepository(
            db.questDao(),
            db.completionDao(),
            prefs,
            aiDiagnostics = diag,
            openAiAuth = auth,
            openAiResponsesEndpoint = "http://127.0.0.1:1/codex/responses",
        )

        val result = offline.suggestQuests(listOf("rent"))

        assertFalse(result.fromAi)
        assertEquals("Couldn't reach the AI. Check your connection and try again.", result.error)
        // The platform exception stays out of the UI but lands in the exportable log.
        assertTrue("log keeps the plain copy", diag.dump().contains("Couldn't reach the AI"))
        assertTrue("log keeps the raw detail", diag.dump().contains("Exception"))
    }
}
