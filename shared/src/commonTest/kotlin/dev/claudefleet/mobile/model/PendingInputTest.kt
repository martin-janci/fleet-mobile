package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [PendingInput.fingerprint] is the identity the screen checks immediately
 * before a key goes out, so what it does and does not notice decides which
 * taps are refused. The desktop's `answerFingerprint` (`pending_input.ts`)
 * decides the same way, by the same rules.
 */
class PendingInputTest {
    private fun dialog(question: String?, vararg options: Pair<Int, String>) =
        PendingInput("permission", question, options.map { PendingOption(it.first, it.second) })

    /**
     * The hub stores `question.or(selected)`, so for a dialog whose lines end
     * in no `?` the field is the HIGHLIGHTED option's own line. Moving the
     * cursor with an arrow key would otherwise read as a different question
     * and refuse the key the person then pressed.
     */
    @Test
    fun moving_the_cursor_is_the_same_dialog() {
        val onYes = dialog("1. Yes", 1 to "Yes", 2 to "No")
        val onNo = dialog("2. No", 1 to "Yes", 2 to "No")
        assertEquals(onYes.fingerprint(), onNo.fingerprint())
        // The hub's other choice spelling, and the one with no question at all.
        assertEquals(onYes.fingerprint(), dialog("2) No", 1 to "Yes", 2 to "No").fingerprint())
        assertEquals(onYes.fingerprint(), dialog(null, 1 to "Yes", 2 to "No").fingerprint())
    }

    /** A real question is still identity — that is the dialog's subject. */
    @Test
    fun a_question_of_its_own_still_tells_two_dialogs_apart() {
        assertNotEquals(
            dialog("Delete the repo?", 1 to "Yes", 2 to "No").fingerprint(),
            dialog("Push to main?", 1 to "Yes", 2 to "No").fingerprint(),
        )
    }

    /** The options are identity: a different menu is a different dialog. */
    @Test
    fun a_different_menu_is_a_different_dialog() {
        assertNotEquals(
            dialog("Allow?", 1 to "Yes", 2 to "No").fingerprint(),
            dialog("Allow?", 1 to "Yes", 2 to "Yes, and don't ask again", 3 to "No").fingerprint(),
        )
        assertNotEquals(
            dialog("Allow?", 1 to "Yes").fingerprint(),
            PendingInput("input", "Allow?", listOf(PendingOption(1, "Yes"))).fingerprint(),
        )
    }

    /**
     * And a menu of the SAME SIZE whose labels or numbers moved.
     *
     * `a_different_menu_is_a_different_dialog` changes the option COUNT, which
     * a fingerprint of `options.size` alone would also catch. These are the
     * cases that need the pairs themselves: the digit a chip presses is an
     * ordinal into that list, so a re-label or a re-number means the same tap
     * picks something the person did not read.
     */
    @Test
    fun a_relabelled_or_renumbered_option_is_a_different_dialog() {
        val asked = dialog("Allow?", 1 to "Yes", 2 to "No")
        assertNotEquals(asked.fingerprint(), dialog("Allow?", 1 to "Allow", 2 to "No").fingerprint())
        assertNotEquals(asked.fingerprint(), dialog("Allow?", 2 to "Yes", 3 to "No").fingerprint())
        assertNotEquals(
            asked.fingerprint(),
            dialog("Allow?", 2 to "No", 1 to "Yes").fingerprint(),
            "the order is the order the REPL drew them in",
        )
    }

    /**
     * The known blind spot, recorded rather than closed here.
     *
     * Two permission dialogs with no question line and the same options are
     * identical to this function even when they are about different commands:
     * the hub puts the command on a description line, which `pending_input`
     * does not carry. So the guard means "a dialog of the same SHAPE", and
     * closing it needs a content hash from the hub — a wire change, not a
     * change here. This test exists so the limit is a decision on the record
     * and not a surprise to whoever next reads the guard.
     */
    @Test
    fun two_dialogs_of_the_same_shape_are_not_told_apart() {
        assertEquals(
            dialog(null, 1 to "Yes", 2 to "No").fingerprint(),
            dialog(null, 1 to "Yes", 2 to "No").fingerprint(),
        )
    }

    /** Which option the REPL highlights is not identity either. */
    @Test
    fun the_selection_glyph_is_not_identity() {
        val a = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes", selected = true), PendingOption(2, "No")))
        val b = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes"), PendingOption(2, "No", selected = true)))
        assertEquals(a.fingerprint(), b.fingerprint())
    }
}
