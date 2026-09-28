package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The words of the shared filter chrome, which a device test would otherwise be the only thing to read. */
class FilterControlsTest {

    @Test
    fun the_filters_entry_counts_in_brackets_and_says_nothing_at_zero() {
        assertEquals("Filters", filtersButtonLabel(0))
        assertEquals("Filters (2)", filtersButtonLabel(2))
    }

    @Test
    fun the_archived_row_says_how_many_are_hidden_and_who_waits() {
        assertEquals("1 archived session hidden", archivedRowText(1, showing = false, noun = "sessions"))
        assertEquals("3 archived tasks hidden", archivedRowText(3, showing = false, noun = "tasks"))
        assertEquals("2 archived sessions hidden · 1 needs you", archivedRowText(2, showing = false, noun = "sessions", attention = 1))
        assertEquals("4 archived sessions hidden · 2 need you", archivedRowText(4, showing = false, noun = "sessions", attention = 2))
        assertEquals("Showing archived tasks", archivedRowText(0, showing = true, noun = "tasks"))
    }

    /** The desktop's empty states: what is on, named, in one sentence. */
    @Test
    fun the_empty_states_name_the_filters() {
        assertEquals("No sessions match Host: gpu-box, Last active within 24 hours.", emptySessionsSentence("Host: gpu-box, Last active within 24 hours"))
        assertEquals("No tasks match Org: Acme, Status: Done.", emptyWorkSentence("Org: Acme, Status: Done"))
    }
}
