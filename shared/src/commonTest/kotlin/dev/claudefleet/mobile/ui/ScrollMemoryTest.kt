package dev.claudefleet.mobile.ui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [ScrollMemory] is a singleton object, so every test clears it afterwards. */
class ScrollMemoryTest {

    @AfterTest
    fun cleanup() {
        ScrollMemory.clear()
    }

    @Test
    fun a_non_bottom_anchor_round_trips() {
        val anchor = ScrollAnchor(firstVisibleIndex = 5, firstVisibleOffset = 40, atBottom = false)

        ScrollMemory.remember(1L, anchor)

        assertEquals(anchor, ScrollMemory.recall(1L))
    }

    @Test
    fun an_at_bottom_anchor_is_dropped_rather_than_stored() {
        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 5, firstVisibleOffset = 40, atBottom = true))

        assertNull(ScrollMemory.recall(1L))
    }

    @Test
    fun returning_to_the_bottom_clears_an_earlier_remembered_position() {
        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 5, firstVisibleOffset = 40, atBottom = false))

        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 20, firstVisibleOffset = 0, atBottom = true))

        assertNull(ScrollMemory.recall(1L))
    }

    @Test
    fun recall_for_a_session_never_remembered_is_null() {
        assertNull(ScrollMemory.recall(999L))
    }

    @Test
    fun sessions_are_remembered_independently() {
        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 3, firstVisibleOffset = 1, atBottom = false))
        ScrollMemory.remember(2L, ScrollAnchor(firstVisibleIndex = 7, firstVisibleOffset = 2, atBottom = false))

        assertEquals(3, ScrollMemory.recall(1L)?.firstVisibleIndex)
        assertEquals(7, ScrollMemory.recall(2L)?.firstVisibleIndex)
    }

    @Test
    fun a_later_non_bottom_anchor_replaces_an_earlier_one() {
        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 3, firstVisibleOffset = 1, atBottom = false))

        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 9, firstVisibleOffset = 6, atBottom = false))

        assertEquals(9, ScrollMemory.recall(1L)?.firstVisibleIndex)
    }

    @Test
    fun clear_drops_everything_remembered() {
        ScrollMemory.remember(1L, ScrollAnchor(firstVisibleIndex = 3, firstVisibleOffset = 1, atBottom = false))
        ScrollMemory.remember(2L, ScrollAnchor(firstVisibleIndex = 7, firstVisibleOffset = 2, atBottom = false))

        ScrollMemory.clear()

        assertNull(ScrollMemory.recall(1L))
        assertNull(ScrollMemory.recall(2L))
    }
}
