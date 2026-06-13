package com.questloop.app.data

import com.questloop.core.model.Difficulty
import com.questloop.core.model.Priority
import com.questloop.core.model.Quest
import com.questloop.core.model.QuestCategory
import com.questloop.core.model.QuestFrequency
import com.questloop.core.model.QuestOrigin

/** A small, friendly starter set so a fresh install isn't an empty screen. */
object SampleData {
    val starterQuests: List<Quest> = listOf(
        Quest(
            id = "seed-water",
            title = "Drink a glass of water",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.TRIVIAL,
            rationale = "An easy daily win to start the streak.",
        ),
        Quest(
            id = "seed-walk",
            title = "Take a 15-minute walk",
            category = QuestCategory.HEALTH,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.EASY,
            estimatedMinutes = 15,
        ),
        Quest(
            id = "seed-inbox",
            title = "Clear your inbox to zero",
            category = QuestCategory.LIFE_ADMIN,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.MEDIUM,
            priority = Priority.NORMAL,
        ),
        Quest(
            id = "seed-deepwork",
            title = "One 50-minute focus block",
            category = QuestCategory.WORK_STUDY,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.HARD,
            priority = Priority.HIGH,
            estimatedMinutes = 50,
        ),
        Quest(
            id = "seed-call",
            title = "Call a friend or family member",
            category = QuestCategory.SOCIAL,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.EASY,
        ),
        Quest(
            id = "seed-noscroll",
            title = "Stay under your doomscrolling limit today",
            category = QuestCategory.BAD_HABIT_REDUCTION,
            frequency = QuestFrequency.DAILY,
            difficulty = Difficulty.MEDIUM,
            isReductionQuest = true,
            rationale = "Honest tracking counts even on tough days.",
        ),
        Quest(
            id = "seed-reward-fund",
            title = "Set up your external rewards fund",
            category = QuestCategory.META_MAINTENANCE,
            frequency = QuestFrequency.ONE_OFF,
            difficulty = Difficulty.EASY,
            origin = QuestOrigin.SYSTEM_RECURRING,
            rationale = "Create a separate savings pot you control — QuestLoop never touches your money.",
        ),
        Quest(
            id = "seed-review",
            title = "Review this week's quests",
            category = QuestCategory.META_MAINTENANCE,
            frequency = QuestFrequency.WEEKLY,
            difficulty = Difficulty.TRIVIAL,
        ),
    )
}
