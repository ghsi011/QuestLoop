package com.questloop.app.ui

import java.time.LocalDate

/** Small indirection over "today" so screens share one notion of the date. */
object AppClock {
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun startOfWeek(today: Long): Long {
        val date = LocalDate.ofEpochDay(today)
        return date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()
    }

    fun startOfMonth(today: Long): Long {
        val date = LocalDate.ofEpochDay(today)
        return date.withDayOfMonth(1).toEpochDay()
    }
}
