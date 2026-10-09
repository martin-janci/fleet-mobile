package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.QuickReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session forms' rules (MobileFormsSession): what each sheet's verb sends. */
class SessionFormsTest {

    // --- rename and tags: one sheet, one Save ---

    @Test
    fun save_sends_the_name_and_the_tags_that_changed() {
        val edit = sessionEdit("Hosts polish", listOf("mobile"), "  Hosts screen polish ", listOf("mobile", "orbit"))
        assertEquals(SessionEdit("Hosts screen polish", listOf("mobile", "orbit")), edit)
        assertTrue(edit.changes)
    }

    @Test
    fun save_leaves_out_what_did_not_change() {
        assertEquals(SessionEdit(null, listOf("orbit")), sessionEdit("A", listOf("mobile"), "A ", listOf("orbit")))
        assertEquals(SessionEdit("B", null), sessionEdit("A", listOf("mobile"), "B", listOf("mobile")))
        assertFalse(sessionEdit("A", listOf("mobile"), "A", listOf("mobile")).changes)
    }

    @Test
    fun a_blank_name_is_never_sent_and_keeps_save_off() {
        assertNull(sessionEdit("A", emptyList(), "   ", emptyList()).name)
        assertFalse(canSaveEdit("  "))
        assertTrue(canSaveEdit("A"))
    }

    @Test
    fun plus_tag_adds_a_trimmed_tag_once() {
        assertEquals(listOf("mobile", "orbit"), withTag(listOf("mobile"), " orbit "))
        assertEquals(listOf("mobile"), withTag(listOf("mobile"), "mobile"))
        assertEquals(listOf("mobile"), withTag(listOf("mobile"), "  "))
    }

    // --- edit quick reply ---

    @Test
    fun a_quick_reply_saves_trimmed_with_send_on_tap() {
        assertEquals(
            QuickReply(label = "Rebase", text = "Rebase on main and re-run your tests.", autoSend = false),
            quickReplyFrom(" Rebase ", " Rebase on main and re-run your tests. ", sendOnTap = false),
        )
        assertTrue(quickReplyFrom("", "go", sendOnTap = true)!!.sendsOnTap)
        assertNull(quickReplyFrom("Label", "  ", sendOnTap = true), "no prompt, no Save")
    }

    // --- fork ---

    private fun t(prompt: String?, uuid: String?, reply: String = "") =
        ConvTurn(prompt = prompt, promptUuid = uuid, items = if (reply.isEmpty()) emptyList() else listOf(ConvItem.Text(reply)))

    private val turns = listOf(
        t("start", "u1", "Read the hosts screen."),
        t("polish", "u2", "Verified the ping column renders on every row"),
        t("tests", "u3", "Screenshot tests pass."),
    )

    @Test
    fun the_from_picker_lists_every_turn_newest_first_with_its_anchor() {
        val choices = forkTurnChoices(turns, truncated = false, supported = true)
        assertEquals(listOf(2, 1, 0), choices.map { it.index })
        // Fork keeps through the turn, so it anchors on the NEXT prompt; the newest keeps all.
        assertEquals(listOf(null, "u3", "u2"), choices.map { it.anchor })
        assertEquals("Turn 2 · “Verified the ping column…”", choices[1].label)
        assertEquals("Turn 3 · “Screenshot tests pass.”", choices[0].label)
    }

    @Test
    fun a_loaded_tail_says_how_far_back_rather_than_a_wrong_number() {
        val labels = forkTurnChoices(turns, truncated = true, supported = true).map { it.label.substringBefore(" ·") }
        assertEquals(listOf("Latest turn", "1 turn back", "2 turns back"), labels)
    }

    @Test
    fun no_fork_choices_on_a_hub_without_rewind() {
        assertTrue(forkTurnChoices(turns, truncated = false, supported = false).isEmpty())
    }

    @Test
    fun new_worktree_needs_a_usable_name_and_sends_its_slug() {
        val form = ForkForm(name = "Try The Other Way ")
        assertEquals("try-the-other-way", form.worktree)
        assertTrue(form.canFork)
        assertFalse(ForkForm(name = "///").canFork)
    }

    @Test
    fun same_worktree_sends_no_name_whatever_was_typed() {
        val form = ForkForm(newWorktree = false, name = "///")
        assertNull(form.worktree)
        assertTrue(form.canFork)
    }

    @Test
    fun the_fork_sheet_says_what_is_not_carried() {
        assertEquals("Uncommitted changes are not carried.", forkNote(newWorktree = true))
        assertEquals("Both sessions edit the same files.", forkNote(newWorktree = false))
    }

    // --- background agent ---

    @Test
    fun the_background_agent_sheet_speaks_the_boards_words() {
        assertEquals("Runs without a pane; the result lands in Inbox.", BACKGROUND_AGENT_META)
        assertEquals("It may read and run tests, never edit or push", BACKGROUND_READ_ONLY_SUB)
    }
}
