package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.SessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** The timeline's categories, held to the desktop's `timeline.ts`. */
class TimelineTest {

    private fun e(kind: String, detail: String? = null, id: Long = 1) = SessionEvent(id = id, kind = kind, detail = detail)

    @Test
    fun events_fall_into_the_desktop_categories() {
        assertEquals(EventCategory.Errors, eventCategory(e("stuck")))
        assertEquals(EventCategory.Errors, eventCategory(e("repair_failed")))
        assertEquals(EventCategory.Errors, eventCategory(e("hook_error")))
        assertEquals(EventCategory.Errors, eventCategory(e("stop_block_cap_reached")))
        assertEquals(EventCategory.Turns, eventCategory(e("turn_done")))
        assertEquals(EventCategory.Prompts, eventCategory(e("prompt_sent")))
        assertEquals(EventCategory.Prompts, eventCategory(e("keys_sent")))
        assertEquals(EventCategory.Ops, eventCategory(e("killed")))
        assertEquals(EventCategory.Ops, eventCategory(e("safe_kill_requested")))
        assertEquals(EventCategory.Ops, eventCategory(e("task_started")))
        assertEquals(EventCategory.Other, eventCategory(e("session_moved")))
    }

    @Test
    fun a_status_change_is_an_error_only_when_it_went_failed_or_blocked() {
        assertEquals(EventCategory.Errors, eventCategory(e("status_change", "working -> blocked")))
        assertEquals(EventCategory.Errors, eventCategory(e("status_change", "working → Failed")))
        assertEquals(EventCategory.Turns, eventCategory(e("status_change", "working -> idle")))
        // A word, not a substring: "unblocked" is not "blocked".
        assertEquals(EventCategory.Turns, eventCategory(e("status_change", "unblocked")))
    }

    @Test
    fun no_filter_shows_everything_and_a_filter_shows_its_categories() {
        val events = listOf(e("turn_done", id = 1), e("stuck", id = 2), e("killed", id = 3))
        assertEquals(events, filterEvents(events, emptySet()))
        assertEquals(listOf(2L, 3L), filterEvents(events, setOf(EventCategory.Errors, EventCategory.Ops)).map { it.id })
    }

    @Test
    fun a_detail_is_one_line_and_capped() {
        assertEquals("a b c", shortDetail("a\n  b\tc"))
        assertEquals("abcd…", shortDetail("abcdefgh", max = 5))
        assertEquals("", shortDetail(null))
        assertEquals("turn done", kindLabel("turn_done"))
    }
}
