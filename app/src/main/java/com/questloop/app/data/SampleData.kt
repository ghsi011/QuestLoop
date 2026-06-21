package com.questloop.app.data

import com.questloop.core.model.CompletionStyle
import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin

/**
 * First-run content. A fresh install starts almost empty on purpose: just two
 * guided "get started" quests (plus the daily routine) so the user builds their
 * own list rather than inheriting a generic one. The richer set lives in the
 * Quest Bank, which the user pulls from deliberately.
 */
object SampleData {

    const val ONBOARDING_PICK = "onboarding-pick"
    const val ONBOARDING_CREATE = "onboarding-create"

    /** The only quests seeded on first run — guided, high-priority, one-off. */
    val onboardingQuests: List<Quest> = listOf(
        Quest(
            id = ONBOARDING_PICK,
            title = "Pick your first quest",
            category = QuestCategory.LIFE_ADMIN,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.TRIVIAL,
            priority = Priority.HIGH,
            origin = QuestOrigin.SYSTEM_RECURRING,
            estimatedMinutes = 1,
            rationale = "Open the Quest Bank and add one that fits your life.",
        ),
        Quest(
            id = ONBOARDING_CREATE,
            title = "Create your first quest",
            category = QuestCategory.LIFE_ADMIN,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.TRIVIAL,
            priority = Priority.HIGH,
            origin = QuestOrigin.SYSTEM_RECURRING,
            estimatedMinutes = 2,
            rationale = "Add something you already want to get done.",
        ),
    )
}

/**
 * A curated catalog of ready-made quests the user can add from the Quests tab.
 * Ids are stable (prefixed `bank-`) so the screen can show what's already added
 * and re-adding simply un-archives the same quest.
 */
object QuestBank {

    val catalog: List<Quest> = listOf(
        // Daily
        Quest(
            id = "bank-water",
            title = "Stay hydrated",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.EASY,
            completionStyle = CompletionStyle.QUANTITATIVE,
            targetCount = 8,
            unit = "glasses",
            rationale = "Log each glass — partial progress still counts.",
        ),
        Quest(
            id = "bank-walk",
            title = "Take a 15-minute walk",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.EASY,
            estimatedMinutes = 15,
        ),
        Quest(
            id = "bank-focus",
            title = "One 50-minute focus block",
            category = QuestCategory.WORK_STUDY,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.HARD,
            priority = Priority.HIGH,
            estimatedMinutes = 50,
            completionStyle = CompletionStyle.DURATION,
            rationale = "Log the minutes you actually focused — even 20 counts.",
        ),
        Quest(
            id = "bank-create",
            title = "Make progress on a creative project",
            category = QuestCategory.PERSONAL_GROWTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.MEDIUM,
            completionStyle = CompletionStyle.SUBJECTIVE,
            rationale = "Hard to measure — just rate how it went. Showing up is the win.",
        ),
        Quest(
            id = "bank-noscroll",
            title = "Stay under your doomscrolling limit",
            category = QuestCategory.BAD_HABIT_REDUCTION,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.MEDIUM,
            isReductionQuest = true,
            rationale = "Honest tracking counts even on tough days.",
        ),
        // Weekly
        Quest(
            id = "bank-inbox",
            title = "Clear your inbox to zero",
            category = QuestCategory.LIFE_ADMIN,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.MEDIUM,
        ),
        Quest(
            id = "bank-call",
            title = "Call a friend or family member",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.EASY,
        ),
        Quest(
            id = "bank-tidy",
            title = "Tidy one room",
            category = QuestCategory.CHORES,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.EASY,
            estimatedMinutes = 20,
        ),
        // Monthly
        Quest(
            id = "bank-goals",
            title = "Review your goals and budget",
            category = QuestCategory.PERSONAL_GROWTH,
            frequency = QuestFrequency.MONTHLY,
            difficulty = Difficulty.MEDIUM,
            rationale = "A monthly check-in on what matters and what's affordable.",
        ),
        Quest(
            id = "bank-deepclean",
            title = "Deep-clean one space",
            category = QuestCategory.CHORES,
            frequency = QuestFrequency.MONTHLY,
            difficulty = Difficulty.MEDIUM,
            estimatedMinutes = 45,
        ),
        // One-off
        Quest(
            id = "bank-reward-fund",
            title = "Set up your external rewards fund",
            category = QuestCategory.META_MAINTENANCE,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
            origin = QuestOrigin.SYSTEM_RECURRING,
            rationale = "Create a separate savings pot you control.",
        ),
        Quest(
            id = "bank-declutter",
            title = "Declutter one drawer or shelf",
            category = QuestCategory.CHORES,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
            estimatedMinutes = 15,
        ),
    )
}
