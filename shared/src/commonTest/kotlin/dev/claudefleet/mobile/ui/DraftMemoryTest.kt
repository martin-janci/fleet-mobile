package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A draft outlives the process (R09 F1): Android kills a cached app freely,
 * and the person who typed a long prompt and went to grab something from
 * another app came back to an empty box.
 */
class DraftMemoryTest {
    @Test
    fun a_draft_kept_by_one_process_is_recalled_by_the_next() {
        val prefs = FakePrefs()
        DraftMemory(prefs).keep(42, "half a thought\nwith a second line")

        val next = DraftMemory(prefs)
        assertEquals("half a thought\nwith a second line", next.recall(42))
    }

    @Test
    fun a_sent_draft_is_dropped_from_the_store() {
        val prefs = FakePrefs()
        val drafts = DraftMemory(prefs)
        drafts.keep(42, "ship it")
        drafts.keep(42, "")

        assertEquals("", DraftMemory(prefs).recall(42))
        assertTrue(prefs.getStringList(DraftMemory.KEY).isEmpty())
    }

    @Test
    fun the_store_keeps_only_the_most_recent_boxes_and_cuts_long_ones() {
        val prefs = FakePrefs()
        val drafts = DraftMemory(prefs)
        for (id in 1L..(DraftMemory.MAX_SESSIONS + 5)) drafts.keep(id, "draft $id")
        drafts.keep(100, "x".repeat(DraftMemory.MAX_CHARS * 2))

        val next = DraftMemory(prefs)
        assertEquals("", next.recall(1))
        assertEquals("draft ${DraftMemory.MAX_SESSIONS + 5}", next.recall(DraftMemory.MAX_SESSIONS + 5L))
        assertEquals(DraftMemory.MAX_CHARS, next.recall(100).length)
        assertTrue(prefs.getStringList(DraftMemory.KEY).size <= DraftMemory.MAX_SESSIONS * 2)
    }

    @Test
    fun a_damaged_store_reads_as_no_drafts() {
        val prefs = FakePrefs()
        prefs.putStringList(DraftMemory.KEY, listOf("not a number", "text", "7"))
        val drafts = DraftMemory(prefs)
        assertEquals("", drafts.recall(7))
    }

    @Test
    fun without_prefs_it_stays_in_memory() {
        val drafts = DraftMemory()
        drafts.keep(1, "here")
        assertEquals("here", drafts.recall(1))
    }
}
