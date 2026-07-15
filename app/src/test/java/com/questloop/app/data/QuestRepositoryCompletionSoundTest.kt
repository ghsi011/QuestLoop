package com.questloop.app.data

import androidx.room.Room
import com.questloop.app.data.local.QuestLoopDatabase
import com.questloop.core.model.BadHabit
import com.questloop.core.model.CompletionResult
import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.EnergyCheckIn
import com.questloop.core.model.Goal
import com.questloop.core.model.Habit
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.UserPreferences
import com.questloop.core.model.UserProfile
import com.questloop.core.reward.CompletionChime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The celebration-chime side of a completion: [QuestRepository.CompleteOutcome.sound]
 * carries the right cue for wins, silence for misses, and nothing at all when the
 * user turned completion sounds off. Cue *selection* details live in `:core`'s
 * CompletionSoundCuesTest; this covers the repository's gating and plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestRepositoryCompletionSoundTest {

    private lateinit var db: QuestLoopDatabase
    private lateinit var repo: QuestRepository
    private lateinit var prefs: FakePrefs

    private class FakePrefs : ProfilePreferences {
        val state = MutableStateFlow(UserProfile(preferences = UserPreferences()))
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
        override suspend fun getAiConfig(): AiConfig = AiConfig()
        override suspend fun setAiConfig(config: AiConfig) {}
        override suspend fun isOnboardingComplete(): Boolean = true
        override suspend fun setOnboardingComplete() {}
        override suspend fun getReminderConfig(): ReminderConfig = ReminderConfig()
        override suspend fun setReminderConfig(config: ReminderConfig) {}
        override suspend fun clear() {}
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            QuestLoopDatabase::class.java,
        ).allowMainThreadQueries().build()
        prefs = FakePrefs()
        repo = QuestRepository(db.questDao(), db.completionDao(), prefs)
    }

    @After
    fun tearDown() = db.close()

    private val today = LocalDate.of(2026, 7, 15).toEpochDay()

    private fun quest(
        difficulty: Difficulty = Difficulty.EASY,
        style: CompletionStyle = CompletionStyle.BINARY,
        targetCount: Int? = null,
        id: String = "q-${difficulty.name}-${style.name}",
    ) = Quest(
        id = id,
        title = "Test quest",
        category = QuestCategory.CHORES,
        frequency = QuestFrequency.DAILY,
        difficulty = difficulty,
        completionStyle = style,
        targetCount = targetCount,
    )

    /** Burns the "First Steps" achievement so tier tests hear the plain chime,
     *  not the achievement-unlock triumph. */
    private suspend fun seedFirstCompletion() {
        repo.completeQuest(quest(id = "seed"), today, CompletionResult.COMPLETED)
    }

    @Test
    fun `completing a quest carries the difficulty-tier chime`() = runTest {
        seedFirstCompletion()
        val outcome = repo.completeQuest(quest(Difficulty.EASY), today, CompletionResult.COMPLETED)
        assertEquals(CompletionChime.MINOR, outcome.sound?.chime)
    }

    @Test
    fun `the very first completion celebrates the achievement unlock`() = runTest {
        val outcome = repo.completeQuest(quest(Difficulty.EASY), today, CompletionResult.COMPLETED)
        assertEquals(CompletionChime.TRIUMPH, outcome.sound?.chime)
    }

    @Test
    fun `a skip is silent`() = runTest {
        val outcome = repo.completeQuest(quest(), today, CompletionResult.SKIPPED)
        assertNull(outcome.sound)
    }

    @Test
    fun `partial progress on a counting quest gets the progress bling`() = runTest {
        seedFirstCompletion()
        val q = quest(style = CompletionStyle.QUANTITATIVE, targetCount = 8)
        val outcome = repo.completeMeasured(q, today, value = 3)
        assertEquals(CompletionChime.PROGRESS, outcome.sound?.chime)
    }

    @Test
    fun `crossing the target upgrades progress bling to the full chime`() = runTest {
        seedFirstCompletion()
        val q = quest(style = CompletionStyle.QUANTITATIVE, targetCount = 8)
        repo.completeMeasured(q, today, value = 3)
        val outcome = repo.completeMeasured(q, today, value = 8)
        assertNotNull(outcome.sound)
        assertEquals(CompletionChime.MINOR, outcome.sound?.chime)
    }

    @Test
    fun `a re-log that adds no progress is silent`() = runTest {
        seedFirstCompletion()
        val q = quest(style = CompletionStyle.QUANTITATIVE, targetCount = 8)
        repo.completeMeasured(q, today, value = 3)
        val outcome = repo.completeMeasured(q, today, value = 3)
        assertNull(outcome.sound)
    }

    @Test
    fun `a below-max subjective rating earns the tier chime`() = runTest {
        seedFirstCompletion()
        val q = quest(difficulty = Difficulty.HARD, style = CompletionStyle.SUBJECTIVE)
        val outcome = repo.completeMeasured(q, today, value = 4)
        assertEquals(CompletionChime.MAJOR, outcome.sound?.chime)
    }

    @Test
    fun `sounds off silences every completion`() = runTest {
        prefs.state.value = UserProfile(preferences = UserPreferences(completionSoundsEnabled = false))
        val outcome = repo.completeQuest(quest(Difficulty.EPIC), today, CompletionResult.COMPLETED)
        assertNull(outcome.sound)
    }
}
