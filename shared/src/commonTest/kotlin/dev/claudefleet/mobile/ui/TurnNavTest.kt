package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TurnNavTest {

    @Test
    fun steps_to_the_next_turn_with_no_truncation_note() {
        // Items: turn0, turn1, turn2. Sitting on turn0, next is turn1.
        assertEquals(1, adjacentTurn(firstVisibleIndex = 0, turnCount = 3, truncated = false, delta = 1))
    }

    @Test
    fun steps_to_the_previous_turn_with_no_truncation_note() {
        assertEquals(0, adjacentTurn(firstVisibleIndex = 1, turnCount = 3, truncated = false, delta = -1))
    }

    @Test
    fun is_disabled_going_forward_from_the_last_turn() {
        // Items: turn0, turn1, turn2 — index 2 is the last turn.
        assertNull(adjacentTurn(firstVisibleIndex = 2, turnCount = 3, truncated = false, delta = 1))
    }

    @Test
    fun is_disabled_going_backward_from_the_first_turn() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 3, truncated = false, delta = -1))
    }

    @Test
    fun accounts_for_the_truncation_note_offset() {
        // Items: note, turn0, turn1, turn2 — turn0 sits at item index 1.
        assertEquals(2, adjacentTurn(firstVisibleIndex = 1, turnCount = 3, truncated = true, delta = 1))
        assertEquals(1, adjacentTurn(firstVisibleIndex = 2, turnCount = 3, truncated = true, delta = -1))
    }

    @Test
    fun next_from_the_truncation_note_itself_lands_on_the_first_turn() {
        // Sitting on the note (item 0), "next" should reach turn0 (item 1).
        assertEquals(1, adjacentTurn(firstVisibleIndex = 0, turnCount = 3, truncated = true, delta = 1))
    }

    @Test
    fun previous_from_the_truncation_note_itself_has_nowhere_to_go() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 3, truncated = true, delta = -1))
    }

    @Test
    fun is_disabled_going_forward_from_the_last_turn_behind_a_note() {
        // Items: note, turn0, turn1, turn2 — turn2 (the last) sits at item 3.
        assertNull(adjacentTurn(firstVisibleIndex = 3, turnCount = 3, truncated = true, delta = 1))
    }

    @Test
    fun an_empty_conversation_has_no_adjacent_turn_in_either_direction() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 0, truncated = false, delta = 1))
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 0, truncated = false, delta = -1))
    }

    @Test
    fun a_single_turn_has_no_adjacent_turn_in_either_direction() {
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 1, truncated = false, delta = 1))
        assertNull(adjacentTurn(firstVisibleIndex = 0, turnCount = 1, truncated = false, delta = -1))
    }
}
