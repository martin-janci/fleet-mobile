package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun turn(at: String?, prompt: String?, vararg items: String) = ConvTurn(
    prompt = prompt,
    at = at,
    endedAt = at,
    items = items.map { ConvItem.Text(it) },
)

private fun conversation(vararg turns: ConvTurn, truncated: Boolean = false) =
    Conversation(turns = turns.toList(), truncated = truncated)

/**
 * `session_conversation` answers with a rolling window of the tail, so a second
 * read is never a continuation — it is an overlapping view of the same list that
 * may have slid forward. Replacing wholesale loses what scrolled off the top of
 * the hub's window but is still on the screen; appending wholesale repeats every
 * turn the two reads share.
 */
class ConversationMergeTest {

    @Test
    fun the_first_read_is_kept_as_it_came() {
        val fresh = conversation(turn("t1", "hello", "hi"))

        assertEquals(fresh, Conversation().appending(fresh))
    }

    @Test
    fun a_reread_of_the_same_window_repeats_nothing() {
        val first = conversation(turn("t1", "a"), turn("t2", "b"))

        assertEquals(first.turns, first.appending(first).turns)
    }

    @Test
    fun turns_that_are_new_are_appended_at_the_bottom() {
        val have = conversation(turn("t1", "a"), turn("t2", "b"))
        val fresh = conversation(turn("t2", "b"), turn("t3", "c"))

        assertEquals(listOf("t1", "t2", "t3"), have.appending(fresh).turns.map { it.at })
    }

    /**
     * The window slid past `t1` entirely. It is still on the screen and the
     * person can still scroll to it, so it stays.
     */
    @Test
    fun a_window_that_slid_forward_does_not_take_the_older_turns_with_it() {
        val have = conversation(turn("t1", "a"), turn("t2", "b"), turn("t3", "c"))
        val fresh = conversation(turn("t3", "c"), turn("t4", "d"))

        assertEquals(listOf("t1", "t2", "t3", "t4"), have.appending(fresh).turns.map { it.at })
    }

    /**
     * The turn at the bottom is the live one: the agent is still adding items to
     * it. It must be replaced by its newer self, not duplicated beneath itself.
     */
    @Test
    fun the_last_turn_growing_replaces_it_rather_than_repeating_it() {
        val have = conversation(turn("t1", "a", "thinking"))
        val fresh = conversation(turn("t1", "a", "thinking", "done"))

        val merged = have.appending(fresh)
        assertEquals(1, merged.turns.size)
        assertEquals(listOf("thinking", "done"), merged.turns.single().items.map { it.label })
    }

    /**
     * Two turns can share a timestamp to the second; the prompt is what tells
     * them apart, and the merge must not fold one into the other.
     */
    @Test
    fun two_turns_at_the_same_instant_are_told_apart_by_their_prompts() {
        val have = conversation(turn("t1", "first"))
        val fresh = conversation(turn("t1", "second"))

        assertEquals(listOf("first", "second"), have.appending(fresh).turns.map { it.prompt })
    }

    /**
     * More than the window's worth happened while the app was backgrounded, so
     * the two reads do not overlap at all. Nothing is dropped and nothing is
     * repeated; [Conversation.truncated] is how the screen says there is a gap.
     */
    @Test
    fun two_reads_that_do_not_overlap_keep_both_and_stay_marked_truncated() {
        val have = conversation(turn("t1", "a"), turn("t2", "b"))
        val fresh = conversation(turn("t9", "z"), truncated = true)

        val merged = have.appending(fresh)
        assertEquals(listOf("t1", "t2", "t9"), merged.turns.map { it.at })
        assertTrue(merged.truncated)
    }

    /** Once the hub has said older turns were dropped, that stays true. */
    @Test
    fun truncation_already_reported_is_not_forgotten_by_a_later_read() {
        val have = conversation(turn("t1", "a"), truncated = true)
        val fresh = conversation(turn("t1", "a"), truncated = false)

        assertTrue(have.appending(fresh).truncated)
        assertFalse(Conversation().appending(fresh).truncated)
    }

    @Test
    fun an_empty_read_changes_nothing() {
        val have = conversation(turn("t1", "a"))

        assertSame(have, have.appending(Conversation()))
    }

    /**
     * An assistant turn whose prompt lies before the read tail has neither an
     * `at` nor a `prompt` to be known by. It must still not be duplicated on
     * every poll.
     */
    @Test
    fun a_turn_with_nothing_to_be_known_by_is_still_not_repeated() {
        val orphan = ConvTurn(items = listOf(ConvItem.Text("output with no prompt")))
        val have = conversation(orphan)

        assertEquals(1, have.appending(conversation(orphan)).turns.size)
    }
}
