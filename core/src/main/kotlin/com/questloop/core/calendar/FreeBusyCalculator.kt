package com.questloop.core.calendar

/**
 * A half-open time span within a single day, measured in minutes from midnight
 * (`startMinute` inclusive, `endMinute` exclusive). Used for both calendar busy
 * blocks and the free gaps between them.
 */
data class Interval(val startMinute: Int, val endMinute: Int) {
    /** Length in minutes; never negative. */
    val durationMinutes: Int get() = (endMinute - startMinute).coerceAtLeast(0)
}

/**
 * The waking window a daily plan may draw on, in minutes from midnight. Calendar
 * busy time is only subtracted within this window, so overnight events and the
 * small hours never count against (or inflate) the day's free time. For "today",
 * the app layer can start the window at the current time to get *remaining* free
 * time rather than the whole day.
 */
data class DayWindow(
    val startMinute: Int = DEFAULT_DAY_START_MINUTE,
    val endMinute: Int = DEFAULT_DAY_END_MINUTE,
) {
    companion object {
        const val DEFAULT_DAY_START_MINUTE = 8 * 60 // 08:00
        const val DEFAULT_DAY_END_MINUTE = 22 * 60 // 22:00
    }
}

/**
 * Turns a day's calendar busy blocks into the free time a plan can use — the
 * bridge from "what's on your calendar" to the [com.questloop.core.generation.QuestGenerator]'s
 * `availableMinutes` budget (SPEC §10 calendar integration; §3 time budgeting).
 *
 * Pure and platform-agnostic: it operates on minute-of-day intervals that the app
 * layer derives from the device calendar (no Android, no I/O here), so the
 * clip/merge/subtract math is fully unit-testable. Overlapping and out-of-window
 * events are handled so busy time is never double-counted nor counted outside
 * waking hours.
 */
object FreeBusyCalculator {

    /** Busy blocks clipped to [window] and merged so overlaps aren't double-counted. */
    fun mergedBusy(busy: List<Interval>, window: DayWindow = DayWindow()): List<Interval> {
        val clipped = busy
            .map {
                Interval(
                    startMinute = it.startMinute.coerceAtLeast(window.startMinute),
                    endMinute = it.endMinute.coerceAtMost(window.endMinute),
                )
            }
            .filter { it.durationMinutes > 0 }
            .sortedBy { it.startMinute }
        if (clipped.isEmpty()) return emptyList()

        val merged = mutableListOf(clipped.first())
        for (next in clipped.drop(1)) {
            val last = merged.last()
            if (next.startMinute <= last.endMinute) {
                // Overlapping or adjacent — extend the current block.
                merged[merged.lastIndex] = Interval(last.startMinute, maxOf(last.endMinute, next.endMinute))
            } else {
                merged += next
            }
        }
        return merged
    }

    /** The open gaps in [window] after removing busy time — when you're actually free. */
    fun freeIntervals(busy: List<Interval>, window: DayWindow = DayWindow()): List<Interval> {
        val merged = mergedBusy(busy, window)
        val free = mutableListOf<Interval>()
        var cursor = window.startMinute
        for (block in merged) {
            if (block.startMinute > cursor) free += Interval(cursor, block.startMinute)
            cursor = maxOf(cursor, block.endMinute)
        }
        if (cursor < window.endMinute) free += Interval(cursor, window.endMinute)
        return free
    }

    /** Total free minutes in [window] — feeds the daily plan's time budget. */
    fun freeMinutes(busy: List<Interval>, window: DayWindow = DayWindow()): Int =
        freeIntervals(busy, window).sumOf { it.durationMinutes }
}
