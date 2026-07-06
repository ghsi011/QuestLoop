package com.questloop.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.achievements.AchievementsViewModel
import com.questloop.app.ui.add.AddQuestViewModel
import com.questloop.app.ui.completed.CompletedViewModel
import com.questloop.app.ui.habits.HabitsViewModel
import com.questloop.app.ui.quests.QuestBankViewModel
import com.questloop.app.ui.quests.QuestsViewModel
import com.questloop.app.ui.review.ReviewViewModel
import com.questloop.app.ui.rewards.RewardsViewModel
import com.questloop.app.ui.settings.SettingsViewModel
import com.questloop.app.ui.today.TodayViewModel
import com.questloop.app.widget.QuickAddViewModel

/** Builds a [ViewModelProvider.Factory] wiring every ViewModel to the repository. */
fun appViewModelFactory(repository: QuestRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { TodayViewModel(repository) }
    initializer { QuickAddViewModel(repository) }
    initializer { QuestsViewModel(repository) }
    initializer { QuestBankViewModel(repository) }
    initializer { AddQuestViewModel(repository) }
    initializer { ReviewViewModel(repository) }
    initializer { RewardsViewModel(repository) }
    initializer { SettingsViewModel(repository) }
    initializer { HabitsViewModel(repository) }
    initializer { AchievementsViewModel(repository) }
    initializer { CompletedViewModel(repository) }
}
