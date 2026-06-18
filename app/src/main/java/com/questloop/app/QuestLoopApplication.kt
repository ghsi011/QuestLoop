package com.questloop.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.questloop.app.di.AppContainer
import com.questloop.app.widget.QuestWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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
        // Map each source to Unit and merge, so any change triggers one refresh.
        // debounce() coalesces bursts (e.g. an import upserting many completions)
        // into a single widget update. catch() is a crash-guard: if a source flow
        // throws (a backing-store hiccup), it's swallowed so it can't take down
        // appScope — the collector then ends quietly and the widget simply stops
        // refreshing until the next app start, rather than crashing the process.
        merge(
            repo.quests.map { },
            repo.completions.map { },
            repo.profile.map { },
        )
            .debounce(500)
            .onEach { runCatching { QuestWidget().updateAll(this@QuestLoopApplication) } }
            .catch { /* crash-guard only; see above — does not resume the collector */ }
            .launchIn(appScope)
    }
}
