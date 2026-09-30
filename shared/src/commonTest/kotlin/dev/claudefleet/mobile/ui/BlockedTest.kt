package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HUB_VERSION_DIGIT_KEYS
import dev.claudefleet.mobile.net.HUB_VERSION_KEYS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockedTest {
    private fun row(status: String? = "blocked", stuck: String? = null, activity: String? = null, pending: PendingInput? = null) =
        SessionRow(id = 1, tmuxName = "s", claudeStatus = status, stuckKind = stuck, currentActivity = activity, pendingInput = pending)

    @Test fun not_blocked_means_no_card() { assertNull(blockedCard(row(status = "working"), HUB_VERSION_KEYS)) }

    @Test
    fun options_become_answers_and_the_question_is_the_headline() {
        val p = PendingInput("permission", "Recreate turanga?", listOf(PendingOption(1, "Yes", true), PendingOption(3, "No")))
        val card = blockedCard(row(pending = p, activity = "waiting for input: ☐ Recreate turanga?"), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals("Recreate turanga?", card.headline)
        assertEquals(listOf(Answer.Option(1, "Yes"), Answer.Option(3, "No"), Answer.Enter, Answer.Escape), card.answers)
    }

    /**
     * A 0.2.35 hub takes Enter / Esc / C-c as keys but not a digit, and it
     * refuses a typed "1" into a blocked session — so it gets no option chip
     * it would refuse, only the keys and the terminal.
     */
    @Test
    fun a_hub_without_digit_keys_offers_no_option_chips() {
        val p = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes"), PendingOption(2, "No")))
        val card = blockedCard(row(pending = p), HUB_VERSION_KEYS)!!
        assertEquals("Allow?", card.headline)
        assertEquals(listOf(Answer.Enter, Answer.Escape), card.answers)
        assertTrue(card.terminalAvailable)
    }

    /** No keystroke picks option 10: pressing "1" then "0" would answer option 1. */
    @Test
    fun an_option_above_nine_has_no_chip() {
        val options = (1..10).map { PendingOption(it, "choice $it") }
        val card = blockedCard(row(pending = PendingInput("input", "Pick one", options)), HUB_VERSION_DIGIT_KEYS)!!
        val offered = card.answers.filterIsInstance<Answer.Option>().map { it.n }
        assertEquals((1..9).toList(), offered)
    }

    @Test
    fun without_pending_input_the_headline_comes_from_the_activity_line_and_only_keys_are_offered() {
        val card = blockedCard(row(activity = "waiting for input: ☐ Recreate turanga?"), HUB_VERSION_KEYS)!!
        assertEquals("☐ Recreate turanga?", card.headline)
        assertEquals(listOf(Answer.Enter, Answer.Escape), card.answers)
    }

    @Test
    fun an_old_hub_offers_the_terminal_and_no_key_chips() {
        val card = blockedCard(row(activity = "waiting for input: x?"), hubVersion = "0.2.34")!!
        assertTrue(card.answers.isEmpty()); assertTrue(card.terminalAvailable)
    }

    @Test
    fun a_hub_that_named_no_version_offers_no_key_chips_but_the_terminal_stays_available() {
        val card = blockedCard(row(activity = "waiting for input: x?"), hubVersion = null)!!
        assertTrue(card.answers.isEmpty()); assertTrue(card.terminalAvailable)
    }

    @Test
    fun stuck_kinds_map_to_fixed_cards() {
        assertEquals(listOf(Answer.Enter), blockedCard(row(stuck = "press_enter"), HUB_VERSION_KEYS)!!.answers)
        assertEquals(listOf(Answer.Enter, Answer.Escape), blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_KEYS)!!.answers)
        val auth = blockedCard(row(stuck = "auth_menu"), HUB_VERSION_KEYS)!!
        assertTrue(auth.answers.isEmpty()); assertTrue(auth.offerRestart); assertEquals("Needs a login on this host", auth.explain)
        assertTrue(blockedCard(row(stuck = "oom"), HUB_VERSION_KEYS)!!.offerRestart)
    }

    /**
     * The trust dialog is a menu, and the hub refuses typed text into a stuck
     * session (E_INVALID_STATE) — so it is answered with keys, and an old hub
     * that cannot take keys offers none rather than a chip it would refuse.
     */
    @Test
    fun the_trust_prompt_is_answered_with_keys_never_typed_text() {
        val card = blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_KEYS)!!
        assertTrue(card.answers.none { it is Answer.Text }, "${card.answers}")
        val old = blockedCard(row(stuck = "trust_prompt"), hubVersion = "0.2.34")!!
        assertTrue(old.answers.isEmpty()); assertTrue(old.terminalAvailable)
    }
}
