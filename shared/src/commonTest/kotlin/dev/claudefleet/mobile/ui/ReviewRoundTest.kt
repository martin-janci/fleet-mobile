@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.appending
import kotlin.test.Test
import kotlin.test.assertEquals

private fun turn(prompt: String?, at: String?) = ConvTurn(prompt = prompt, at = at)

/**
 * Task 5 review, N1 — **identity drift duplicates a whole window.**
 *
 * `appending` splices on the longest overlap between the tail of what is held
 * and the **head** of what arrived. So if the identity of the first arrived turn
 * has changed since it was last read, the overlap collapses to zero and the
 * entire fresh window is appended underneath the held one, repeating every turn
 * they share. It never self-corrects: `appending` drops nothing, so the
 * duplicate stays for the life of the screen.
 *
 * Both ways the identity can drift are the hub's, not ours: `at` for a headless
 * turn is its *first assistant entry's* timestamp, which moves as the hub's 1 MB
 * tail slides off it, and `fit_last_turn` cuts an over-budget prompt from its
 * **head**. Narrow, and neither is impossible.
 */
class ConversationIdentityDriftTest {

    /**
     * The window is no longer repeated. The drifted turn itself still appears
     * twice — once under each identity — and that is the honest limit of what
     * can be known: nothing on the wire says the two are the same turn. One
     * duplicated turn instead of a duplicated window is the whole of the fix.
     */
    @Test
    fun a_leading_turn_whose_timestamp_moved_does_not_duplicate_the_window() {
        val held = Conversation(
            listOf(turn(null, "2026-09-18T10:00:05Z"), turn("p2", "t2"), turn("p3", "t3")),
        )
        val fresh = Conversation(
            // The same headless leading turn, re-stamped by the hub as its 1 MB
            // tail slid off the entry the old stamp came from.
            listOf(
                turn(null, "2026-09-18T10:00:09Z"),
                turn("p2", "t2"),
                turn("p3", "t3"),
                turn("p4", "t4"),
            ),
        )

        val merged = held.appending(fresh)

        assertEquals(
            listOf("p2", "p3", "p4"),
            merged.turns.mapNotNull { it.prompt },
            "a prompted turn was repeated: ${merged.turns.map { it.prompt }}",
        )
        // Before the fix this was 7 — the whole held window under the whole
        // fresh one. It is 5: the four fresh turns, plus the stale reading of
        // the one turn whose identity moved.
        assertEquals(5, merged.turns.size)
    }

    @Test
    fun a_leading_prompt_the_hub_trimmed_from_its_head_does_not_duplicate_the_window() {
        val held = Conversation(listOf(turn("…a very long prompt", "t1"), turn("p2", "t2")))
        val fresh = Conversation(
            listOf(turn("…very long prompt", "t1"), turn("p2", "t2"), turn("p3", "t3")),
        )

        val merged = held.appending(fresh)

        assertEquals(1, merged.turns.count { it.prompt == "p2" }, "p2 was repeated")
        assertEquals(1, merged.turns.count { it.prompt == "p3" })
        assertEquals(4, merged.turns.size)
    }

    /**
     * The invariant the fix is really about, stated once over both drift cases:
     * no two turns in the merged list share an identity. A list that repeats one
     * is a list that will repeat it for the life of the screen, because
     * `appending` drops nothing.
     */
    @Test
    fun no_turn_appears_twice_under_the_same_identity() {
        val held = Conversation(
            listOf(turn(null, "2026-09-18T10:00:05Z"), turn("p2", "t2"), turn("p3", "t3")),
        )
        val fresh = Conversation(
            listOf(turn(null, "2026-09-18T10:00:09Z"), turn("p2", "t2"), turn("p3", "t3")),
        )

        val identities = held.appending(fresh).turns.map { it.at to it.prompt }

        assertEquals(identities.size, identities.toSet().size, "a repeated turn: $identities")
    }

    /**
     * The guard must not eat a genuinely disjoint history. When the windows do
     * not overlap and the held turns are genuinely *older*, both are kept.
     */
    @Test
    fun genuinely_disjoint_windows_are_still_both_kept() {
        val held = Conversation(listOf(turn("p1", "t1"), turn("p2", "t2")))
        val fresh = Conversation(listOf(turn("p8", "t8"), turn("p9", "t9")))

        val merged = held.appending(fresh)

        assertEquals(listOf("p1", "p2", "p8", "p9"), merged.turns.mapNotNull { it.prompt })
    }

    /** And the ordinary sliding case, re-pinned so the guard cannot break it. */
    @Test
    fun the_ordinary_sliding_window_still_splices_exactly() {
        var conversation = Conversation()
        val all = (1..40).map { turn("p$it", "t$it") }
        for (start in 0..30) {
            conversation = conversation.appending(Conversation(all.subList(start, start + 10)))
        }
        assertEquals((1..40).map { "p$it" }, conversation.turns.mapNotNull { it.prompt })
    }
}

/**
 * Task 5 review, S1 — the auto-scroll lands on the wrong turn.
 *
 * `scrollToItem` takes a **LazyColumn item index**, and the truncation note
 * occupies index 0 whenever `truncated` is set — which is the normal case, since
 * the hub sets it on any conversation longer than its window. So the target was
 * one short and the screen settled on the second-to-last turn, with the newest
 * one below the fold: exactly the turn the screen exists to show.
 *
 * The index is a pure function so that it can be asserted at all; nothing in
 * this repository can render a `LazyColumn`.
 */
class ConversationScrollTest {

    @Test
    fun the_newest_turn_is_the_last_item_not_the_last_turn() {
        // Three turns, no note: items are turn0, turn1, turn2.
        assertEquals(2, newestItemIndex(turns = 3, truncated = false))
        // Three turns behind a note: items are note, turn0, turn1, turn2.
        assertEquals(3, newestItemIndex(turns = 3, truncated = true))
    }

    @Test
    fun an_empty_conversation_has_nothing_to_scroll_to() {
        assertEquals(null, newestItemIndex(turns = 0, truncated = false))
        // Not index 0: that is the note, and jumping to it on an empty
        // conversation would be scrolling to a header.
        assertEquals(null, newestItemIndex(turns = 0, truncated = true))
    }

    @Test
    fun one_turn_is_index_zero_or_one_depending_on_the_note() {
        assertEquals(0, newestItemIndex(turns = 1, truncated = false))
        assertEquals(1, newestItemIndex(turns = 1, truncated = true))
    }
}
