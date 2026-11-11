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

    @Test
    fun the_task_screen_line_and_the_chip_flag_a_date_that_has_passed() {
        assertEquals(DueLabel("Due Fri · 2026-11-06", false), dueLine("2026-11-06", today, done = false))
        assertEquals(DueLabel("Overdue · was due 2026-10-30", true), dueLine("2026-10-30", today, done = false))
        assertNull(dueLine("2026-10-30", today, done = true), "a done task shows no due date (the desktop's openDue)")
        assertEquals(DueLabel("Due: next week", false), dueLine("next week", today, done = false))
        assertNull(dueLine(" ", today, done = false))
    }

    /** The desktop's `openDue`: "Due Fri" / "Overdue · Oct 30", nothing once done, nothing for a date that does not parse. */
    @Test
    fun open_due_is_the_desktops_chip() {
        assertEquals(DueLabel("Due Tomorrow", false), openDue("2026-11-05", done = false, todayEpochDay = today))
        assertEquals(DueLabel("Overdue · Oct 30", true), openDue("2026-10-30", done = false, todayEpochDay = today))
        assertEquals(DueLabel("Due Nov 11", false), openDue(" 2026-11-11 ", done = false, todayEpochDay = today))
        assertNull(openDue("2026-10-30", done = true, todayEpochDay = today))
        assertNull(openDue("next week", done = false, todayEpochDay = today))
        assertNull(openDue(null, done = false, todayEpochDay = today))
    }

    /** One builder for every row: a hot priority, then the open due date; overdue flags the line. */
    @Test
    fun row_facts_lead_with_a_hot_priority_and_the_open_due_date() {
        assertEquals(FactLine("Urgent · Overdue · Oct 30", overdue = true), rowFacts("urgent", "2026-10-30", done = false, todayEpochDay = today))
        assertEquals(FactLine("High · Due Fri"), rowFacts("high", "2026-11-06", done = false, todayEpochDay = today))
        assertEquals(FactLine("Due Fri"), rowFacts("medium", "2026-11-06", done = false, todayEpochDay = today))
        assertEquals(FactLine("High"), rowFacts("high", "2026-10-30", done = true, todayEpochDay = today))
        assertEquals(FactLine("Urgent"), rowFacts("urgent", "2026-10-30", done = false, todayEpochDay = null))
        assertEquals("High", hotPriorityLabel("high"))
        assertNull(hotPriorityLabel("low"))
        assertNull(hotPriorityLabel(null))
    }

    @Test
    fun labels_past_four_are_counted() {
        assertEquals(listOf("a", "b"), labelChips(listOf("a", "b")))
        assertEquals(listOf("a", "b", "c", "d"), labelChips(listOf("a", "b", "c", "d")))
        assertEquals(listOf("a", "b", "c", "d", "+2"), labelChips(listOf("a", "b", "c", "d", "e", "f")))
    }
}
