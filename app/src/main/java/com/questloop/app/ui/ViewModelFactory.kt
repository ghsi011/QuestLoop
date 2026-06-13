package com.questloop.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.questloop.app.data.QuestRepository
import com.questloop.app.ui.add.AddQuestViewModel
import com.questloop.app.ui.review.ReviewViewModel
import com.questloop.app.ui.rewards.RewardsViewModel
import com.questloop.app.ui.today.TodayViewModel

/** Builds a [ViewModelProvider.Factory] wiring every ViewModel to the repository. */
fun appViewModelFactory(repository: QuestRepository): ViewModelProvider.Factory = viewModelFactory {
    initializer { TodayViewModel(repository) }
    initializer { AddQuestViewModel(repository) }
    initializer { ReviewViewModel(repository) }
    initializer { RewardsViewModel(repository) }
}
