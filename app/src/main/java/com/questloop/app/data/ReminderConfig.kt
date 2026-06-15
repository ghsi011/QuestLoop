package com.questloop.app.data

/**
 * Daily reminder settings: an opt-in morning check-in and evening wrap-up nudge
 * for the minimal daily loop (SPEC §3; UX review H1). Times are local hour/minute.
 */
data class ReminderConfig(
    val enabled: Boolean = false,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val eveningHour: Int = 20,
    val eveningMinute: Int = 0,
)
