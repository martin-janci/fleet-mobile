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

/**
 * What the screen keeps, and what it lets go.
 *
 * The merge above is written around the hub sending a *rolling window*: it
 * re-reads the last N turns every time, so the app has to hold the ones that
 * have scrolled off the hub's end but are still on the screen. The consequence
 * nobody had looked at is that the app holds **all** of them, for as long as the
 * screen is open, while the hub's own memory of the session stays the same ten
 * turns it always was.
 *
 * These are Claude Code sessions. They run for hours and produce hundreds of
 * turns, each carrying its prompt and every tool line under it — and the screen
 * that shows one is the screen someone leaves open to watch. Growth is bounded
 * by nothing but how long the agent works.
 *
 * So the retained conversation has a ceiling, and the turns that go are the
 * oldest — the ones furthest from what is happening now. `truncated` is how the
 * screen already says there is more above ("Older turns are not shown"), so
 * dropping a turn and setting that flag is a thing the UI can already express
 * rather than a new state to draw.
 */
class ConversationCeilingTest {

    /** Enough reads to outrun any plausible ceiling, one new turn at a time. */
    private fun longSession(turns: Int): Conversation {
        var held = Conversation()
        for (n in 1..turns) {
            // A rolling window of ten, exactly as the hub answers: the newest
            // turn plus the nine before it.
            val window = ((n - 9).coerceAtLeast(1)..n).map { turn("t$it", "prompt $it", "line $it") }
            held = held.appending(Conversation(turns = window))
        }
        return held
    }

    @Test
    fun a_long_session_does_not_grow_without_bound() {
        val held = longSession(MAX_RETAINED_TURNS * 3)

        assertEquals(
            MAX_RETAINED_TURNS,
            held.turns.size,
            "the screen holds every turn it has ever seen unless something stops it",
        )
    }

    /** The turns that survive are the newest ones, in order. */
    @Test
    fun the_newest_turns_are_the_ones_kept() {
        val total = MAX_RETAINED_TURNS * 2
        val held = longSession(total)

        assertEquals("t${total - MAX_RETAINED_TURNS + 1}", held.turns.first().at)
        assertEquals("t$total", held.turns.last().at, "the live turn must never be the one dropped")
        assertEquals(
            held.turns.map { it.at },
            held.turns.sortedBy { it.at?.removePrefix("t")?.toInt() }.map { it.at },
            "dropping from the head must not reorder what is left",
        )
    }

    /** And the screen is told, in the one way it already knows how to show. */
    @Test
    fun dropping_a_turn_marks_the_conversation_truncated() {
        val held = longSession(MAX_RETAINED_TURNS + 1)

        assertTrue(held.truncated, "`Older turns are not shown` is exactly what has happened")
    }

    /**
     * Exactly at the ceiling: nothing dropped, so nothing claimed.
     *
     * The `<=` in `withinCeiling` was mutable to `<` with the whole suite still
     * green, because the tests either side of it used `MAX - 1` and `MAX * 3`
     * and never the boundary itself. Under `<`, a conversation of exactly 200
     * turns takes the drop path — `takeLast(200)` returns the same 200 turns,
     * so nothing is lost and nothing looks wrong — and sets `truncated`, which
     * the screen renders as "Older turns are not shown" over a conversation
     * that is complete. A lie the size of one turn, and invisible in any test
     * that does not sit exactly on the edge.
     */
    @Test
    fun a_session_exactly_at_the_ceiling_is_not_truncated() {
        val held = longSession(MAX_RETAINED_TURNS)

        assertEquals(MAX_RETAINED_TURNS, held.turns.size)
        assertFalse(held.truncated, "nothing was dropped at exactly the ceiling")
    }

    /** And one turn past it is. */
    @Test
    fun one_turn_past_the_ceiling_is_truncated() {
        assertTrue(longSession(MAX_RETAINED_TURNS + 1).truncated)
    }

    /**
     * A session below the ceiling is untouched — no drops, and no truncation
     * flag invented for a conversation that is complete.
     */
    @Test
    fun a_short_session_is_not_truncated_by_the_ceiling() {
        val held = longSession(MAX_RETAINED_TURNS - 1)

        assertEquals(MAX_RETAINED_TURNS - 1, held.turns.size)
        assertFalse(held.truncated, "nothing was dropped, so nothing should claim it was")
    }

    /**
     * A single read larger than the ceiling is cut too.
     *
     * `conversation(turns = n)` lets a caller ask for more than the default ten,
     * and the ceiling has to hold for the first read as much as the hundredth —
     * a cap that only applies to *merged* turns is one an opening screen walks
     * straight past.
     */
    @Test
    fun one_oversized_read_is_cut_to_the_ceiling() {
        val huge = Conversation(turns = (1..MAX_RETAINED_TURNS + 50).map { turn("t$it", "p$it") })

        val held = Conversation().appending(huge)

        assertEquals(MAX_RETAINED_TURNS, held.turns.size)
        assertEquals("t${MAX_RETAINED_TURNS + 50}", held.turns.last().at)
        assertTrue(held.truncated)
    }

    /** Truncation already reported by the hub still survives the merge. */
    @Test
    fun the_hubs_own_truncation_flag_is_not_lost() {
        val held = Conversation().appending(
            Conversation(turns = listOf(turn("t1", "a")), truncated = true),
        )

        assertTrue(held.truncated)
    }
}
