package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The list is newest-first ([newestFirst]): with three turns the items are
 * turn2 (index 0, at the bottom), turn1, turn0, then the truncation note when
 * there is one. "Previous" is older, so a higher index.
 */
class TurnNavTest {

    @Test
    fun steps_to_the_previous_turn() {
        // On turn2 (item 0), previous is turn1 (item 1).
        assertEquals(1, adjacentTurn(firstVisibleIndex = 0, turnCount = 3, delta = -1))
    }

    @Test
    fun steps_to_the_next_turn() {
        // On turn1 (item 1), next is turn2 (item 0).
        assertEquals(0, adjacentTurn(firstVisibleIndex = 1, turnCount = 3, delta = 1))
    }

    @Test
    fun is_disabled_going_forward_from_the_newest_turn() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 3, delta = 1))
    }

    @Test
    fun is_disabled_going_backward_from_the_oldest_turn() {
        // Item 2 is turn0, the oldest.
        assertNull(adjacentTurn(firstVisibleIndex = 2, turnCount = 3, delta = -1))
    }

    @Test
    fun next_from_the_truncation_note_lands_on_the_oldest_turn() {
        // The note is item 3, after the oldest turn (item 2).
        assertEquals(2, adjacentTurn(firstVisibleIndex = 3, turnCount = 3, delta = 1))
    }

    @Test
    fun previous_from_the_truncation_note_has_nowhere_to_go() {
        assertNull(adjacentTurn(firstVisibleIndex = 3, turnCount = 3, delta = -1))
    }

    @Test
    fun an_empty_conversation_has_no_adjacent_turn_in_either_direction() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 0, delta = 1))
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 0, delta = -1))
    }

    @Test
    fun a_single_turn_has_no_adjacent_turn_in_either_direction() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 1, delta = 1))
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 1, delta = -1))
    }
}
