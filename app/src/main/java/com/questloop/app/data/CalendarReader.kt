package com.questloop.app.data

/**
 * Reads the device calendar to tell the planner how much free time is left today
 * (SPEC §10). Abstracted so the repository stays unit-testable with a fake and the
 * Android `CalendarContract` plumbing lives in [AndroidCalendarReader].
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
}

/** Default: no calendar integration. Used in tests and until the user opts in. */
object NoopCalendarReader : CalendarReader {
    override suspend fun freeMinutesToday(): Int? = null
}
