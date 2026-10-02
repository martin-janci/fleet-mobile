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
 * Task 5 review, S1 — the auto-scroll landed on the wrong turn, because the
 * truncation note sat at item 0 and pushed every turn one index along.
 *
 * The list is newest-first under `reverseLayout` now, so there is no index to
 * get wrong: the newest turn is item 0 — drawn against the bottom edge, which
 * is where a fresh `LazyListState` already is — and the note comes after the
 * oldest turn. What is left to assert is the order and the keys.
 */
class ConversationScrollTest {

    @Test
    fun the_newest_turn_is_item_zero() {
        val rows = newestFirst(listOf(turn("a", "t1"), turn("b", "t2"), turn("c", "t3")))

        assertEquals(listOf("c", "b", "a"), rows.map { it.turn.prompt })
    }

    @Test
    fun an_empty_conversation_has_no_rows() {
        assertEquals(emptyList(), newestFirst(emptyList()))
    }

    /**
     * Keys belong to the turn, not its position: a turn arriving at the
     * newest end, or the oldest one dropped at the ceiling, leaves every
     * other turn's key alone — which is what lets the list hold a scrolled-up
     * reader in place.
     */
    @Test
    fun a_turn_keeps_its_key_as_turns_arrive_and_leave() {
        val before = newestFirst(listOf(turn("a", "t1"), turn("b", "t2"))).associate { it.turn.prompt to it.key }
        val after = newestFirst(listOf(turn("b", "t2"), turn("c", "t3"))).associate { it.turn.prompt to it.key }

        assertEquals(before["b"], after["b"])
    }

    /** `LazyColumn` throws on a duplicate key, and two headless turns in one second share an identity. */
    @Test
    fun turns_sharing_an_identity_still_get_distinct_keys() {
        val rows = newestFirst(listOf(turn(null, null), turn(null, null), turn("a", "t1"), turn("a", "t1")))

        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
