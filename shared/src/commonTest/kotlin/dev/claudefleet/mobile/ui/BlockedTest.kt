package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HUB_VERSION_DIGIT_KEYS
import dev.claudefleet.mobile.net.HUB_VERSION_KEYS
import dev.claudefleet.mobile.ui.components.answerLabel
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

    /**
     * A chip is pressed as its digit, so a repeated ordinal means the chip's
     * label is not the choice the REPL would pick. The hub can read a
     * numbered list the agent printed above the dialog as part of the same
     * block (`n = 1, 2, 1, 2, 3`); nothing here can tell which `1` is meant,
     * so no chip is drawn at all and the terminal stays the way to answer.
     */
    @Test
    fun a_repeated_ordinal_withholds_every_option_chip() {
        val leaked = listOf(
            PendingOption(1, "Add the guard"),
            PendingOption(2, "Run the tests"),
            PendingOption(1, "Yes"),
            PendingOption(2, "Yes, and don't ask again"),
            PendingOption(3, "No"),
        )
        val card = blockedCard(row(pending = PendingInput("permission", "Allow?", leaked)), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(listOf(Answer.Enter, Answer.Escape), card.answers)
        assertTrue(card.terminalAvailable)
    }

    /** A GAP is not a repeat: each digit still names one choice. */
    @Test
    fun a_gap_in_the_ordinals_still_gets_its_chips() {
        val p = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes"), PendingOption(3, "No")))
        val card = blockedCard(row(pending = p), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(listOf(Answer.Option(1, "Yes"), Answer.Option(3, "No"), Answer.Enter, Answer.Escape), card.answers)
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
        // Every answer is a key, by construction: `Answer` has no text arm any
        // more, and the chips it does offer are Enter and Esc.
        assertEquals(listOf(Answer.Enter, Answer.Escape), card.answers)
        val old = blockedCard(row(stuck = "trust_prompt"), hubVersion = "0.2.34")!!
        assertTrue(old.answers.isEmpty()); assertTrue(old.terminalAvailable)
    }

    /**
     * A numbered dialog whose chips are ALL withheld says the choices exist and
     * where to answer them.
     *
     * Each of the three ways the chips go away — an old hub, an option past the
     * 9 a key can press, and a set whose ordinals repeat — drew a bare question
     * with Enter and Esc and nothing else, so nothing on screen said there were
     * numbered choices at all.
     */
    @Test
    fun a_dialog_whose_chips_are_withheld_says_why() {
        val two = listOf(PendingOption(1, "Yes"), PendingOption(2, "No"))
        val old = blockedCard(row(pending = PendingInput("permission", "Allow?", two)), HUB_VERSION_KEYS)!!
        assertEquals("2 numbered choices — this hub is too old to press one; use the terminal", old.explain)

        val many = (1..10).map { PendingOption(it, "choice $it") }
        val big = blockedCard(row(pending = PendingInput("input", "Pick one", many)), HUB_VERSION_DIGIT_KEYS)!!
        assertTrue(big.explain == null, "nine of them ARE pressable: ${big.explain}")

        val past = listOf(PendingOption(10, "ten"), PendingOption(11, "eleven"))
        val none = blockedCard(row(pending = PendingInput("input", "Pick one", past)), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals("2 numbered choices, past the 9 a single key can press — use the terminal", none.explain)

        val leaked = listOf(PendingOption(1, "a"), PendingOption(2, "b"), PendingOption(1, "Yes"))
        val repeated = blockedCard(row(pending = PendingInput("permission", "Allow?", leaked)), HUB_VERSION_DIGIT_KEYS)!!
        assertTrue(repeated.explain!!.contains("numbered twice over"), repeated.explain!!)

        // A free-text prompt has no choices to explain away.
        assertNull(blockedCard(row(activity = "waiting for input: x?"), HUB_VERSION_KEYS)!!.explain)
    }

    /**
     * The card carries the dialog it was built from, and the option the REPL's
     * cursor is on.
     *
     * The first is what the answer guard compares against — taking it from the
     * live row at tap time compared the probe against a row that may already
     * have moved. The second was decoded off the wire and read by nothing, so
     * Enter was an unlabelled "press whatever is highlighted".
     */
    @Test
    fun the_card_carries_the_dialog_it_was_drawn_from_and_the_highlighted_option() {
        val p = PendingInput(
            "permission",
            "Allow?",
            listOf(PendingOption(1, "Yes"), PendingOption(2, "Yes, and don't ask again", selected = true)),
        )
        val card = blockedCard(row(pending = p), HUB_VERSION_DIGIT_KEYS)!!
        assertEquals(p, card.asked)
        assertNull(card.askedStuck)
        assertEquals("Yes, and don't ask again", card.highlighted)

        // A stuck card carries its kind instead, and nothing says it is highlighted.
        val stuck = blockedCard(row(stuck = "trust_prompt"), HUB_VERSION_KEYS)!!
        assertEquals("trust_prompt", stuck.askedStuck)
        assertNull(stuck.highlighted)

        // Nothing selected, or a blank label, leaves Enter unlabelled rather
        // than labelled with nothing.
        val plain = blockedCard(row(pending = PendingInput("permission", "Allow?", listOf(PendingOption(1, "Yes")))), HUB_VERSION_DIGIT_KEYS)!!
        assertNull(plain.highlighted)
        val blank = blockedCard(row(pending = PendingInput("permission", "Allow?", listOf(PendingOption(1, " ", selected = true)))), HUB_VERSION_DIGIT_KEYS)!!
        assertNull(blank.highlighted)
    }

    /**
     * The chips' words, including the one chip whose effect was unreadable.
     *
     * Enter presses whatever the REPL's cursor is on, and beside labelled digit
     * chips an unlabelled "Enter" was the only chip a person could not tell the
     * effect of — on a permission dialog, the difference between approving once
     * and approving for good.
     */
    @Test
    fun the_enter_chip_names_what_it_will_press() {
        assertEquals("Enter", answerLabel(Answer.Enter))
        assertEquals("Enter", answerLabel(Answer.Enter, highlighted = null))
        assertEquals(
            "Enter · Yes, and don't ask again",
            answerLabel(Answer.Enter, highlighted = "Yes, and don't ask again"),
        )
        // The highlight belongs to Enter alone: a digit chip already says which
        // choice it picks, and Esc picks none.
        assertEquals("Esc", answerLabel(Answer.Escape, highlighted = "Yes"))
        assertEquals("2 · No", answerLabel(Answer.Option(2, "No"), highlighted = "Yes"))
    }
}
