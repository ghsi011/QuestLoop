package com.questloop.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.questloop.core.generation.QuestSchedule
import com.questloop.core.model.Quest
import java.time.DayOfWeek

/**
 * Schedules/cancels the per-quest reminder alarms: one one-shot alarm per
 * (quest, scheduled-time slot), armed for the quest's next scheduled day at that
 * time via [QuestSchedule.nextTriggerMillis].
 *
 * Same reliability pattern as the daily nudges ([ReminderScheduler]): one-shot
 * inexact-while-idle alarms that re-arm from the receiver on each fire, with
 * boot / timezone-change / app-start re-arms as safety nets. Inexact keeps us off
 * the SCHEDULE_EXACT_ALARM permission — a reminder landing within the OS's small
 * batching window is fine for a nudge.
 *
 * Alarms for quests that stop being eligible (archived, reminders toggled off,
 * occurrence limit reached, deleted) are not proactively cancelled here — the
 * receiver's fire-time reconcile skips the notification and ends the series, so
 * at worst one silent fire remains. Slots beyond a quest's current time count ARE
 * cleared on every (re)schedule, so shrinking a quest's times leaves no stale slot.
 */
class QuestReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** Re-derives the alarms for every reminder-eligible quest
     *  ([com.questloop.app.data.QuestRepository.reminderQuests]). */
    fun applyAll(quests: List<Quest>, firstDayOfWeek: DayOfWeek) {
        quests.forEach { schedule(it, firstDayOfWeek) }
    }

    /**
     * Arms one alarm per scheduled time and clears the unused slot indexes.
     *
     * [fromMillis] is the instant to search forward from. It defaults to *now* —
     * this path re-runs on every quest/ledger/profile change, and any positive
     * buffer here would silently skip a slot landing inside it (completing some
     * other quest at 19:59 must not swallow tonight's 20:00 dose reminder). Only
     * the receiver's post-fire re-arm passes a buffer, to roll past the instant
     * that just fired.
     */
    fun schedule(quest: Quest, firstDayOfWeek: DayOfWeek, fromMillis: Long = System.currentTimeMillis()) {
        val times = quest.scheduledTimes.take(QuestSchedule.MAX_TIMES_PER_DAY)
        (times.size until QuestSchedule.MAX_TIMES_PER_DAY).forEach { cancelSlot(quest.id, it) }
        times.forEachIndexed { index, minuteOfDay ->
            val triggerAt = QuestSchedule.nextTriggerMillis(
                quest.copy(scheduledTimes = listOf(minuteOfDay)),
                fromMillis,
                firstDayOfWeek,
            ) ?: return@forEachIndexed
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(quest.id, index, minuteOfDay),
            )
        }
    }

    /**
     * Self-heal from the receiver's extras alone: next occurrence of the same
     * wall-clock time (today/tomorrow). The receiver's async reconcile replaces it
     * with the quest's real cadence — but if that read fails, this keeps the
     * series alive instead of silently ending it.
     */
    fun scheduleFallback(questId: String, index: Int, minuteOfDay: Int) {
        val triggerAt = ReminderSchedule.nextTriggerMillis(
            System.currentTimeMillis() + POST_FIRE_BUFFER_MILLIS,
            minuteOfDay / 60,
            minuteOfDay % 60,
        )
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(questId, index, minuteOfDay),
        )
    }

    /** Ends every alarm slot for a quest (called when it's no longer eligible). */
    fun cancelFor(questId: String) {
        (0 until QuestSchedule.MAX_TIMES_PER_DAY).forEach { cancelSlot(questId, it) }
    }

    private fun cancelSlot(questId: String, index: Int) {
        alarmManager?.cancel(pendingIntent(questId, index, minuteOfDay = 0))
    }

    private fun pendingIntent(questId: String, index: Int, minuteOfDay: Int): PendingIntent {
        val intent = Intent(context, QuestReminderReceiver::class.java)
            .setAction(ACTION_QUEST_REMINDER)
            // A distinct data URI per (quest, slot) is what makes the PendingIntents
            // distinct (Intent.filterEquals ignores extras): arbitrary quest-id hash
            // request codes could collide and silently cancel another quest's alarm.
            .setData(Uri.parse("questloop://quest-reminder/$questId/$index"))
            .putExtra(EXTRA_QUEST_ID, questId)
            .putExtra(EXTRA_INDEX, index)
            .putExtra(EXTRA_MINUTE, minuteOfDay)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        const val ACTION_QUEST_REMINDER = "com.questloop.app.QUEST_REMINDER"
        const val EXTRA_QUEST_ID = "questId"
        const val EXTRA_INDEX = "timeIndex"
        const val EXTRA_MINUTE = "minuteOfDay"

        /** The post-fire re-arm buffer: rolls past the instant that just fired even
         *  if the alarm was dispatched marginally early. */
        const val POST_FIRE_BUFFER_MILLIS: Long = 60_000L
    }
}
