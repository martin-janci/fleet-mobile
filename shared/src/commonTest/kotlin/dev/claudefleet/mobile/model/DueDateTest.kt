package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DueDateTest {
    /** 2026-11-04, a Wednesday. */
    private val today = 20_761L

    @Test
    fun a_date_is_counted_in_days_from_1970() {
        assertEquals(0L, epochDayOf("1970-01-01"))
        assertEquals(-1L, epochDayOf("1969-12-31"))
        assertEquals(19_782L, epochDayOf("2024-02-29"))
        assertEquals(today, epochDayOf("2026-11-04"))
        assertNull(epochDayOf("2026-02-29"))
        assertNull(epochDayOf("2026-13-01"))
        assertNull(epochDayOf("Fri"))
    }

    @Test
    fun the_local_day_follows_the_offset() {
        // 2026-11-04T23:30Z is already the 5th an hour east of Greenwich.
        val late = today * 86_400 + 23 * 3_600 + 1_800
        assertEquals(today, localEpochDay(late, 0))
        assertEquals(today + 1, localEpochDay(late, 3_600))
        assertEquals(today, localEpochDay(late, -5 * 3_600))
    }

    @Test
    fun a_due_date_reads_as_the_desktop_says_it() {
        assertEquals(DueLabel("Today", false), dueLabel("2026-11-04", today))
        assertEquals(DueLabel("Tomorrow", false), dueLabel("2026-11-05", today))
        assertEquals(DueLabel("Fri", false), dueLabel("2026-11-06", today))
        assertEquals(DueLabel("Tue", false), dueLabel("2026-11-10", today))
        assertEquals(DueLabel("Nov 11", false), dueLabel("2026-11-11", today))
        assertEquals(DueLabel("Oct 30", true), dueLabel("2026-10-30", today))
        assertNull(dueLabel(null, today))
        assertNull(dueLabel("", today))
        assertNull(dueLabel("soon", today))
    }
}
