package dev.claudefleet.mobile.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

private fun event(tag: String): JsonElement = JsonPrimitive(tag)

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

    // ---- context/events: task 5 review, "zero test coverage" ----
    //
    // `appending()` is a rolling-window merge like `turns` itself, not a
    // ledger: `context` and `events` are the *freshest* read's own picture,
    // replacing what was held, and only fall back to what was held when this
    // particular read carried none at all (a fetch racing ahead of the hub's
    // own context/event bookkeeping). Every case here goes through the
    // ordinary overlap path (`have.turns` is never empty), which is the branch
    // that actually carries the merge logic under review — the first-read
    // branch (`turns.isEmpty()`) is already covered by
    // `the_first_read_is_kept_as_it_came` above.

    @Test
    fun the_freshest_context_replaces_what_was_held() {
        val have = conversation(turn("t1", "a")).copy(context = ConvContext(pct = 10.0))
        val fresh = conversation(turn("t1", "a"), turn("t2", "b")).copy(context = ConvContext(pct = 55.0))

        assertEquals(ConvContext(pct = 55.0), have.appending(fresh).context)
    }

    @Test
    fun a_read_that_carries_no_context_keeps_what_was_held() {
        val have = conversation(turn("t1", "a")).copy(context = ConvContext(pct = 10.0))
        // `fresh` carries no context at all (the hub's tail had no usage yet).
        val fresh = conversation(turn("t1", "a"), turn("t2", "b"))

        assertEquals(ConvContext(pct = 10.0), have.appending(fresh).context)
    }

    @Test
    fun the_freshest_non_empty_events_replace_what_was_held() {
        val have = conversation(turn("t1", "a")).copy(events = listOf(event("old")))
        val fresh = conversation(turn("t1", "a"), turn("t2", "b")).copy(events = listOf(event("new")))

        assertEquals(listOf(event("new")), have.appending(fresh).events)
    }

    @Test
    fun a_read_with_no_events_keeps_what_was_held() {
        val have = conversation(turn("t1", "a")).copy(events = listOf(event("old")))
        // `fresh` carries no events (the default empty list).
        val fresh = conversation(turn("t1", "a"), turn("t2", "b"))

        assertEquals(listOf(event("old")), have.appending(fresh).events)
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

    /**
     * `withinCeiling()` used to rebuild the trimmed [Conversation] from
     * scratch (`Conversation(turns = ..., truncated = true)`), which silently
     * dropped `context`/`events` on exactly the reads big enough to matter —
     * a long-running session's. It now `copy()`s instead, so a merge that
     * crosses [MAX_RETAINED_TURNS] keeps both.
     */
    @Test
    fun a_merge_that_crosses_the_ceiling_still_keeps_context_and_events() {
        var held = Conversation()
        val total = MAX_RETAINED_TURNS + 5
        for (n in 1..total) {
            val window = ((n - 9).coerceAtLeast(1)..n).map { turn("t$it", "prompt $it", "line $it") }
            val fresh = if (n == total) {
                Conversation(turns = window, context = ConvContext(pct = 77.0), events = listOf(event("done")))
            } else {
                Conversation(turns = window)
            }
            held = held.appending(fresh)
        }

        assertEquals(MAX_RETAINED_TURNS, held.turns.size, "the ceiling still trims the turns")
        assertEquals(ConvContext(pct = 77.0), held.context, "the ceiling must not drop the context on the way out")
        assertEquals(listOf(event("done")), held.events, "nor the events")
    }

    /**
     * The splice takes the **longest** overlap, not the first one that matches.
     *
     * A turn's identity is `(at, prompt)` and is not unique. `at` is the hub's
     * timestamp at second resolution, so a prompt repeated inside one second —
     * an agent sending `go` three times, which is what an agent does — produces
     * a run of turns that are indistinguishable to this function. Headless
     * turns are worse: they all share the empty identity, which the KDoc above
     * already says folds them together.
     *
     * That makes several overlap lengths match at once, and only the longest is
     * right. Searching upward finds the shortest, splices too little, and
     * repeats the rest of the run on screen — one duplicated block per read,
     * for as long as the window holds the repetition.
     *
     * Found by mutation: reversing the loop's direction broke nothing any test
     * could see, because every other case here has distinct identities and
     * exactly one overlap to find.
     */
    @Test
    fun the_longest_overlap_is_the_one_spliced() {
        // `go` three times inside second `t2`: three turns, one identity.
        val held = conversation(
            turn("t1", "setup"),
            turn("t2", "go", "first"),
            turn("t2", "go", "second"),
            turn("t2", "go", "third"),
        )
        // The window has slid: it shows the last two `go` turns and a new one.
        val fresh = conversation(
            turn("t2", "go", "second"),
            turn("t2", "go", "third"),
            turn("t5", "next", "answer"),
        )

        val merged = held.appending(fresh)

        assertEquals(
            listOf("setup", "go", "go", "go", "next"),
            merged.turns.map { it.prompt },
            "a two-turn overlap must be spliced as two; matching only the last turn " +
                "keeps one `go` twice",
        )
        assertEquals(5, merged.turns.size, "nothing is repeated: ${merged.turns.map { it.at }}")
    }
}

/**
 * [Conversation.tailMarker] is the one "did the tail move" rule shared by
 * `SessionViewModel`'s `tailGrew` and `SessionScreen`'s auto-scroll
 * `LaunchedEffect` keys — final review fix wave, "one tail rule".
 */
class ConversationTailMarkerTest {

    @Test
    fun an_empty_conversation_has_no_turns_and_no_endedAt() {
        assertEquals(0, Conversation().tailMarker().first)
        assertEquals(null, Conversation().tailMarker().second)
    }

    @Test
    fun the_marker_is_the_turn_count_and_the_last_turns_endedAt() {
        val convo = conversation(turn("t1", "a"), turn("t2", "b"))

        assertEquals(2 to "t2", convo.tailMarker())
    }

    /** A new turn changes the count half of the pair. */
    @Test
    fun a_new_turn_changes_the_marker() {
        val before = conversation(turn("t1", "a"))
        val after = conversation(turn("t1", "a"), turn("t2", "b"))

        assertTrue(before.tailMarker() != after.tailMarker())
    }

    /** The live turn growing changes only `endedAt`, not the count — the case `tailGrew` exists for. */
    @Test
    fun the_live_turn_growing_changes_the_marker_without_changing_the_count() {
        val before = conversation(ConvTurn(prompt = "a", at = "t1", endedAt = "t1"))
        val after = conversation(ConvTurn(prompt = "a", at = "t1", endedAt = "t1-later"))

        assertEquals(before.tailMarker().first, after.tailMarker().first)
        assertTrue(before.tailMarker() != after.tailMarker())
    }

    /** The ordinary "nothing happened" case: an identical tail has an identical marker. */
    @Test
    fun an_identical_tail_has_an_identical_marker() {
        val a = conversation(turn("t1", "a"), turn("t2", "b"))
        val b = conversation(turn("t1", "a"), turn("t2", "b"))

        assertEquals(a.tailMarker(), b.tailMarker())
    }
}
