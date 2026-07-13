package com.questloop.app

import android.app.Application
import androidx.glance.appwidget.updateAll
import com.questloop.app.di.AppContainer
import com.questloop.app.reminders.QuestReminderScheduler
import com.questloop.app.widget.QuestWidget
import com.questloop.app.widget.WidgetRefreshScheduler
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
        keepQuestRemindersArmed()
        // The flows below only emit on writes: nothing re-renders the widget when
        // a date/day-part boundary passes, so also arm the self-healing boundary
        // alarm. Every process start doubles as its re-arm safety net (alarms are
        // lost on reboot/force-stop); a no-op while no widget is placed.
        runCatching { WidgetRefreshScheduler(this).scheduleNext() }
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
        // The ledger source is the signal-only completionsChanged (an O(1) change
        // stamp), NOT repo.completions — this collector lives for the whole process,
        // and the full flow would re-read and re-map the entire (unbounded) completion
        // history on every write just to be discarded here. debounce() coalesces
        // bursts (e.g. an import upserting many completions) into a single widget
        // update, and updateAll() exits after a cheap widget-id lookup when no widget
        // is placed, so a tick without a widget does no render/plan work. catch() is
        // a crash-guard: if a source flow throws (a backing-store hiccup), it's
        // swallowed so it can't take down appScope — the collector then ends quietly
        // and the widget simply stops refreshing until the next app start, rather
        // than crashing the process.
        merge(
            repo.quests.map { },
            repo.completionsChanged,
            repo.profile.map { },
        )
            .debounce(500)
            .onEach { runCatching { QuestWidget().updateAll(this@QuestLoopApplication) } }
            .catch { /* crash-guard only; see above — does not resume the collector */ }
            .launchIn(appScope)
    }

    /**
     * Keep the per-quest reminder alarms matching the data they derive from:
     * re-arm on every quest change (add/edit/archive/import), ledger change (a
     * completion can retire an occurrence-limited quest or shift an anchored
     * quest's next due day), and profile change (the first-day-of-week preference
     * moves weekly anchors). The initial flow emission doubles as the app-start
     * re-arm safety net; alarms set with FLAG_UPDATE_CURRENT replace in place, so
     * re-applying is idempotent. Same debounce/crash-guard shape as the widget
     * refresher above.
     */
    private fun keepQuestRemindersArmed() {
        val repo = container.repository
        merge(
            repo.quests.map { },
            repo.completionsChanged,
            repo.profile.map { },
        )
            .debounce(500)
            .onEach {
                runCatching {
                    QuestReminderScheduler(this@QuestLoopApplication)
                        .applyAll(repo.reminderQuests(), repo.firstDayOfWeek())
                }
            }
            .catch { /* crash-guard; reminders re-arm on next app start / boot / fire */ }
            .launchIn(appScope)
    }
}
