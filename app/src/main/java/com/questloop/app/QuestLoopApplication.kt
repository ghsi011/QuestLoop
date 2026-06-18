package com.questloop.app

import android.app.Application
import com.questloop.app.di.AppContainer
import com.questloop.app.widget.QuestWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class QuestLoopApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        keepWidgetFresh()
    }

    /**
     * Keep the home-screen widget current: it reads today's plan from Room, but
     * the system only refreshes it infrequently, so it would otherwise show stale
     * counts/titles after the user adds or completes a quest. Refresh it whenever
     * the data it depends on changes (active quests, the completion ledger, or the
     * daily budget/profile that shapes the plan).
     */
    private fun keepWidgetFresh() {
        val repo = container.repository
        combine(repo.quests, repo.completions, repo.profile) { _, _, _ -> }
            .drop(1) // skip the initial replay; the OS already draws the first frame
            .onEach { runCatching { QuestWidget().updateAll(this) } }
            .launchIn(appScope)
    }
}
