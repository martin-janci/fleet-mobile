package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [dialogMoved] on its own, arm by arm.
 *
 * It was driven only through [SessionViewModel.answer], and only by fixtures
 * that differed in one field: the one "the dialog is gone" case set
 * `claudeStatus = "working"` with NO `pendingInput`, so `probe.pendingInput?`
 * short-circuited on the null and neither the `claudeStatus == "blocked"` nor
 * the `stuckKind == null` conjunct of the same line was ever evaluated. Each
 * one is a way a key could go out into a pane nobody read.
 */
class DialogMovedTest {
    private fun dialog(q: String?, vararg options: Pair<Int, String>) =
        PendingInput("permission", q, options.map { PendingOption(it.first, it.second) })

    private val asked = dialog("Allow?", 1 to "Yes", 2 to "No")

    private fun probe(
        status: String? = "blocked",
        stuck: String? = null,
        input: PendingInput? = asked,
    ) = ActivityProbe(claudeStatus = status, stuckKind = stuck, pendingInput = input)

    @Test
    fun the_same_dialog_still_on_screen_lets_the_key_through() {
        assertNull(dialogMoved(asked, null, probe(), null))
        assertNull(dialogMoved(asked, null, probe(), Answer.Option(1, "Yes")))
    }

    /**
     * Each conjunct of `pendingInput?.takeIf { stuckKind == null && status ==
     * "blocked" }` on its own. A pane that is WORKING, or that has become stuck,
     * is not showing the dialog that was tapped however much the structured
     * reading still looks like it — the hub leaves the last `pending_input` in
     * place while the state moves.
     */
    @Test
    fun a_pane_that_is_not_blocked_on_this_dialog_is_gone() {
        val gone = "That question is gone"
        assertEquals(gone, dialogMoved(asked, null, probe(status = "working"), null)?.title)
        assertEquals(gone, dialogMoved(asked, null, probe(status = "idle"), null)?.title)
        assertEquals(gone, dialogMoved(asked, null, probe(status = null), null)?.title)
        // Blocked, with the same dialog — but stuck on something else now, so
        // Enter means whatever that something else means.
        assertEquals(gone, dialogMoved(asked, null, probe(stuck = "trust_prompt"), null)?.title)
        // And no dialog at all.
        assertEquals(gone, dialogMoved(asked, null, probe(input = null), null)?.title)
    }

    @Test
    fun a_different_dialog_in_the_same_place_is_a_change() {
        val changed = "The question changed"
        assertEquals(
            changed,
            dialogMoved(asked, null, probe(input = dialog("Push to main?", 1 to "Yes", 2 to "No")), null)?.title,
        )
        // The options moved under the digits: pressing `1` would pick something
        // the person did not read.
        assertEquals(
            changed,
            dialogMoved(asked, null, probe(input = dialog("Allow?", 1 to "No", 2 to "Yes")), null)?.title,
        )
        // A card drawn from a row that carried no dialog at all cannot be
        // compared, so it is never pressed.
        assertEquals(changed, dialogMoved(null, null, probe(), null)?.title)
    }

    /**
     * A named option must still be offered, by BOTH number and label — the
     * clause that stops a chip from pressing a digit the dialog has since given
     * to another choice.
     */
    @Test
    fun a_named_option_must_still_be_on_the_dialog() {
        assertNull(dialogMoved(asked, null, probe(), Answer.Option(2, "No")))
        val relabelled = dialog("Allow?", 1 to "Yes", 2 to "No, and stop asking")
        assertEquals(
            "The question changed",
            dialogMoved(relabelled, null, probe(input = relabelled), Answer.Option(2, "No"))?.title,
        )
    }

    /**
     * The stuck-kind card (the trust prompt) is judged on `stuck_kind` alone:
     * it has no `pending_input` to compare, and the kind is what decides what
     * Enter does.
     */
    @Test
    fun a_stuck_card_is_judged_on_its_kind() {
        assertNull(dialogMoved(null, "trust_prompt", probe(stuck = "trust_prompt", input = null), null))
        assertEquals(
            "That question is gone",
            dialogMoved(null, "trust_prompt", probe(stuck = null, input = null), null)?.title,
        )
        assertEquals(
            "The question changed",
            dialogMoved(null, "trust_prompt", probe(stuck = "press_enter", input = null), null)?.title,
        )
    }

    /** Neither refusal is drawn as an error: nothing broke, the pane moved on. */
    @Test
    fun a_refusal_is_not_an_error_banner() {
        assertTrue(dialogMoved(asked, null, probe(status = "working"), null)!!.isError.not())
        assertTrue(dialogMoved(asked, null, probe(input = dialog("Other?")), null)!!.isError.not())
    }
}
