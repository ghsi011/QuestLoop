package com.questloop.app.data

/** A device calendar event, surfaced so a quest's deadline can be picked from it. */
data class CalendarEventSummary(
    val id: String,
    val title: String,
    val epochDay: Long,
)

/**
 * Reads the device calendar to tell the planner how much free time is left today
 * (SPEC §10), and to let the user pick a quest deadline straight from an upcoming
 * event. Abstracted so the repository/ViewModel stay unit-testable with a fake and
 * the Android `CalendarContract` plumbing lives in [AndroidCalendarReader].
 *
 * Deliberately reads the calendars already synced on the device (Google, Outlook,
 * …) — no Google API, no OAuth, no network — keeping QuestLoop local-first.
 */
interface CalendarReader {
    /**
     * Free minutes remaining today from the device calendar, or null when it
     * shouldn't apply: the feature is off, calendar permission isn't granted, or
     * there's nothing to read. Null means "don't impose a calendar budget".
     */
    suspend fun freeMinutesToday(): Int?

    /**
     * Upcoming events in the next [daysAhead] days, for picking a deadline when
     * adding a quest. Empty (not an error) when permission isn't granted — the
     * caller falls back to picking a date manually.
     */
    suspend fun upcomingEvents(daysAhead: Int = 14): List<CalendarEventSummary>
}

/** Default: no calendar integration. Used in tests and until the user opts in. */
object NoopCalendarReader : CalendarReader {
    override suspend fun freeMinutesToday(): Int? = null
    override suspend fun upcomingEvents(daysAhead: Int): List<CalendarEventSummary> = emptyList()
}
