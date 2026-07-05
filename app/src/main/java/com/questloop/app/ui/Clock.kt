package com.questloop.app.ui

import com.questloop.core.completion.CompletionSlots
import com.questloop.core.model.DayPart
import java.time.LocalDate
import java.time.LocalTime

/** Small indirection over "today" so screens share one notion of the date. */
object AppClock {
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun currentDayPart(): DayPart = DayPart.fromHour(LocalTime.now().hour)

    // Week/month boundaries delegate to :core's CompletionSlots — the same math
    // that keys interval completion slots — so the Completed filters and review/
    // plan windows can never drift from how completions are bucketed.
    fun startOfWeek(today: Long): Long = CompletionSlots.startOfWeek(today)

    fun startOfMonth(today: Long): Long = CompletionSlots.startOfMonth(today)

    /** Last day (inclusive) of the ISO week `today` falls in — for forward-looking plans. */
    fun endOfWeek(today: Long): Long = CompletionSlots.endOfWeek(today)

    /** Last day (inclusive) of the calendar month `today` falls in. */
    fun endOfMonth(today: Long): Long = CompletionSlots.endOfMonth(today)
}
