package com.questloop.app.ui.settings

import android.app.AlarmManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.questloop.app.data.AiConfig
import com.questloop.app.data.ProfilePreferences
import com.questloop.app.data.QuestRepository
import com.questloop.app.data.ReminderConfig
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.app.reminders.ReminderScheduler
import com.questloop.core.model.BadHabit
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * Pins [SettingsScreen]'s reminder (re)scheduling effect to the LOADED config: the
 * placeholder default emitted while the saved config is still loading must never
 * reach the AlarmManager, or every visit to Settings would transiently cancel the
 * user's armed alarms — permanently, if the load fails (launchSafely swallows it).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class SettingsScreenReminderEffectTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var db: QuestLoopDatabase

    /** Minimal prefs fake; [emitProfile]=false never emits, pinning the load in flight. */
    private class FakePrefs(
        private var reminders: ReminderConfig = ReminderConfig(),
        emitProfile: Boolean = true,
    ) : ProfilePreferences {
        override val profile: Flow<UserProfile> =
            if (emitProfile) MutableStateFlow(UserProfile()) else MutableSharedFlow<UserProfile>()
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
        override suspend fun getAiConfig(): AiConfig = AiConfig()
        override suspend fun setAiConfig(config: AiConfig) {}
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = reminders
        override suspend fun setReminderConfig(config: ReminderConfig) { reminders = config }
        override suspend fun clear() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val sync = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor(sync).setTransactionExecutor(sync).build()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun repo(prefs: ProfilePreferences) =
        QuestRepository(db.questDao(), db.completionDao(), prefs)

    // Arm/assert through the host activity: it is the composition's LocalContext,
    // and Robolectric's shadow alarm state is per-AlarmManager instance.
    private fun setContent(vm: SettingsViewModel) = composeRule.setContent {
        SettingsScreen(vm, onOpenHabits = {}, snackbarHostState = SnackbarHostState())
    }

    @Test
    fun `placeholder state while the config loads leaves armed alarms alone`() {
        val activity = composeRule.activity
        val shadow = shadowOf(activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        // Alarms armed by a previous session (e.g. re-armed on app open).
        ReminderScheduler(activity).apply(ReminderConfig(enabled = true))
        assertEquals(2, shadow.scheduledAlarms.size)

        val vm = SettingsViewModel(repo(FakePrefs(emitProfile = false)))
        setContent(vm)
        composeRule.waitForIdle()

        assertTrue(vm.state.value.loading)
        assertEquals(2, shadow.scheduledAlarms.size)
    }

    @Test
    fun `the loaded config is applied once it arrives`() {
        val activity = composeRule.activity
        val shadow = shadowOf(activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertTrue(shadow.scheduledAlarms.isEmpty())

        val saved = ReminderConfig(enabled = true, morningHour = 7, eveningHour = 21)
        val vm = SettingsViewModel(repo(FakePrefs(reminders = saved)))
        setContent(vm)
        composeRule.waitForIdle()

        assertFalse(vm.state.value.loading)
        assertEquals(2, shadow.scheduledAlarms.size)
    }
}
