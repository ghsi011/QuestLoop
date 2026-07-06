package com.questloop.app.ui

import com.questloop.core.completion.CompletionSlots
import com.questloop.core.model.DayPart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Small indirection over "today" so screens share one notion of the date. */
object AppClock {
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun currentDayPart(): DayPart = DayPart.fromHour(LocalTime.now().hour)

    // Week/month boundaries delegate to :core's CompletionSlots — the same math
    // that keys interval completion slots — so the Completed filters and review/
    // plan windows can never drift from how completions are bucketed. The week's
    // first day is the user's preference (default Sunday); callers pass it so the
    // "this week" windows match how weekly quests reset.
    fun startOfWeek(today: Long, firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): Long =
        CompletionSlots.startOfWeek(today, firstDayOfWeek)

    fun startOfMonth(today: Long): Long = CompletionSlots.startOfMonth(today)

    /** Last day (inclusive) of the week `today` falls in (starts on [firstDayOfWeek]) — for forward-looking plans. */
    fun endOfWeek(today: Long, firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): Long =
        CompletionSlots.endOfWeek(today, firstDayOfWeek)

    /** Last day (inclusive) of the calendar month `today` falls in. */
    fun endOfMonth(today: Long): Long = CompletionSlots.endOfMonth(today)
}
