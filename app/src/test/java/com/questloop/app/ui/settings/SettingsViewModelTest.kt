package com.questloop.app.ui.settings

import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.AiProvider
import com.questloop.app.data.OpenAiAuth
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.ai.openai.OpenAiOAuth
import java.io.IOException
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository

    /** Fake OAuth backend: signIn surfaces a URL then returns a fixed result; no sockets. */
    private class FakeAuth(
        var result: Result<OpenAiOAuth.OpenAiTokens> = Result.success(
            OpenAiOAuth.OpenAiTokens("at", "rt", accountId = "acct", expiresAtEpochSec = Long.MAX_VALUE),
        ),
    ) : OpenAiAuth {
        var openedUrl: String? = null
        override suspend fun signIn(
            timeoutMs: Long,
            onTokens: suspend (OpenAiOAuth.OpenAiTokens) -> Unit,
            openUrl: (String) -> Unit,
        ): Result<OpenAiOAuth.OpenAiTokens> {
            openUrl("https://auth.openai.test/authorize?x=1")
            openedUrl = "https://auth.openai.test/authorize?x=1"
            // Mirror the real service: tokens are handed to the caller's hook
            // (where the repository persists them) before returning success.
            result.getOrNull()?.let { onTokens(it) }
            return result
        }
        override suspend fun refresh(tokens: OpenAiOAuth.OpenAiTokens) = Result.success(tokens)
    }

    private val fakeAuth = FakeAuth()

    /** Persists everything Settings reads back after a write. */
    private class FakePrefs : ProfilePreferences {
        private val state = MutableStateFlow(UserProfile())
        override val profile: Flow<UserProfile> = state
        override suspend fun setBudgetCap(value: Double) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(monthlyRewardBudgetCap = value),
            )
        }
        override suspend fun setMaxDaily(value: Int) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(maxDailyQuests = value),
            )
        }
        override suspend fun setAvailableMinutes(value: Int) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(defaultAvailableMinutes = value),
            )
        }
        override suspend fun setFocusCategories(cats: Set<QuestCategory>) {
            state.value = state.value.copy(
                preferences = state.value.preferences.copy(focusCategories = cats),
            )
        }
        override suspend fun setStreakGraceDays(value: Int) {}
        override suspend fun setSensitiveOptIn(value: Boolean) {}
        override suspend fun setHabits(habits: List<Habit>) {
            state.value = state.value.copy(habits = habits)
        }
        override suspend fun setBadHabits(badHabits: List<BadHabit>) {
            state.value = state.value.copy(badHabits = badHabits)
        }
        override suspend fun setGoals(goals: List<Goal>) {
            state.value = state.value.copy(goals = goals)
        }
        override suspend fun setCheckIn(checkIn: EnergyCheckIn?) {}
        override suspend fun getCheckIn(): EnergyCheckIn? = null
        private var ai = AiConfig()
        override suspend fun getAiConfig(): AiConfig = ai
        override suspend fun setAiConfig(config: AiConfig) { ai = config }
        private var onboarded = false
        override suspend fun isOnboardingComplete(): Boolean = onboarded
        override suspend fun setOnboardingComplete() { onboarded = true }
        private var reminders = ReminderConfig()
        override suspend fun getReminderConfig(): ReminderConfig = reminders
        override suspend fun setReminderConfig(config: ReminderConfig) { reminders = config }
        override suspend fun clear() {
            state.value = UserProfile()
            ai = AiConfig()
            onboarded = false
            reminders = ReminderConfig()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sync = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor(sync).setTransactionExecutor(sync).build()
        repo = QuestRepository(db.questDao(), db.completionDao(), FakePrefs(), openAiAuth = fakeAuth)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `init loads preferences and finishes loading`() = runTest {
        val vm = SettingsViewModel(repo)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `saving the ai config persists the key and reports success`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.saveAi(enabled = true, apiKey = "sk-test ", model = "m", filterWording = true)
        val state = vm.state.value
        assertEquals("sk-test", state.ai.apiKey)
        assertTrue(state.ai.enabled)
        assertEquals("AI settings saved", state.savedMessage)
    }

    @Test
    fun `setting max daily emits a confirmation`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setMaxDaily(5)
        assertEquals(5, vm.state.value.prefs.maxDailyQuests)
        assertNotNull(vm.state.value.savedMessage)
    }

    @Test
    fun `setting available minutes persists`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setAvailableMinutes(90)
        assertEquals(90, vm.state.value.prefs.defaultAvailableMinutes)
    }

    @Test
    fun `toggling a focus category adds then removes it`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.toggleFocus(QuestCategory.HEALTH)
        assertTrue(vm.state.value.prefs.focusCategories.contains(QuestCategory.HEALTH))
        vm.toggleFocus(QuestCategory.HEALTH)
        assertFalse(vm.state.value.prefs.focusCategories.contains(QuestCategory.HEALTH))
    }

    @Test
    fun `setting reminders persists and reports state`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setReminders(ReminderConfig(enabled = true, morningHour = 7, eveningHour = 21))
        assertTrue(vm.state.value.reminders.enabled)
        assertEquals("Reminders on", vm.state.value.savedMessage)
    }

    // Note: requestExport()/importData()/shareDiagnostics() are intentionally not
    // asserted here. They delegate to repository.exportJson()/importJson()/
    // aiDiagnosticsDump(), which hop to Dispatchers.IO; under runTest that escapes
    // the virtual-time scheduler, so the resulting state update can't be observed
    // deterministically (the assertion races the background work). Those paths are
    // covered deterministically at the repository layer instead (QuestRepositoryTest,
    // QuestRepositoryCompletionStylesTest, QuestRepositoryBranchesTest).

    @Test
    fun `consume saved message clears it`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setMaxDaily(4)
        assertNotNull(vm.state.value.savedMessage)
        vm.consumeSavedMessage()
        assertNull(vm.state.value.savedMessage)
    }

    @Test
    fun `selecting the OpenAI provider switches the backend`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.setProvider(AiProvider.OPENAI)
        assertEquals(AiProvider.OPENAI, vm.state.value.ai.provider)
    }

    @Test
    fun `saving OpenAI settings persists the model and provider`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.saveOpenAi(enabled = true, model = "gpt-5-codex ", filterWording = false)
        val state = vm.state.value
        assertEquals(AiProvider.OPENAI, state.ai.provider)
        assertEquals("gpt-5-codex", state.ai.openAiModel)
        assertFalse(state.ai.filterWording)
        assertEquals("AI settings saved", state.savedMessage)
    }

    @Test
    fun `connecting ChatGPT links the account and opens the browser url`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.connectOpenAi()
        val state = vm.state.value
        assertTrue(state.ai.openAiConnected)
        assertEquals(AiProvider.OPENAI, state.ai.provider)
        assertNotNull("the browser url is surfaced for the screen to open", state.openAuthUrl)
        assertEquals("Connected to ChatGPT", state.savedMessage)
        assertFalse(state.aiBusy)
    }

    @Test
    fun `a failed ChatGPT sign-in reports a friendly message and stays disconnected`() = runTest {
        fakeAuth.result = Result.failure(IOException("Unable to resolve host \"auth.openai.com\""))
        val vm = SettingsViewModel(repo)
        vm.connectOpenAi()
        val state = vm.state.value
        assertFalse(state.ai.openAiConnected)
        assertEquals("Couldn't sign in to ChatGPT. Please try again.", state.savedMessage)
    }

    @Test
    fun `disconnecting ChatGPT unlinks the account`() = runTest {
        val vm = SettingsViewModel(repo)
        vm.connectOpenAi()
        assertTrue(vm.state.value.ai.openAiConnected)
        vm.disconnectOpenAi()
        assertFalse(vm.state.value.ai.openAiConnected)
        assertEquals("Disconnected from ChatGPT", vm.state.value.savedMessage)
    }

    @Test
    fun `delete all data clears state and invokes the callback`() = runTest {
        val vm = SettingsViewModel(repo)
        var done = false
        vm.deleteAllData { done = true }
        assertTrue(done)
        assertEquals("Your data has been deleted.", vm.state.value.savedMessage)
    }
}
