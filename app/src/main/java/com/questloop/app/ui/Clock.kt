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
}
