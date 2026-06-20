package com.questloop.app.ui

import com.questloop.core.model.DayPart
import java.time.LocalDate
import java.time.LocalTime

/** Small indirection over "today" so screens share one notion of the date. */
object AppClock {
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun currentDayPart(): DayPart = DayPart.fromHour(LocalTime.now().hour)

    fun startOfWeek(today: Long): Long {
        val date = LocalDate.ofEpochDay(today)
        return date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    fun startOfMonth(today: Long): Long {
        val date = LocalDate.ofEpochDay(today)
        return date.withDayOfMonth(1).toEpochDay()
    }

    /** Last day (inclusive) of the ISO week `today` falls in — for forward-looking plans. */
    fun endOfWeek(today: Long): Long = startOfWeek(today) + 6

    /** Last day (inclusive) of the calendar month `today` falls in. */
    fun endOfMonth(today: Long): Long {
        val date = LocalDate.ofEpochDay(today)
        return date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
    }
}
