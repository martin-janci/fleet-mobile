package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Activity
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HUB_VERSION_DIGIT_KEYS
import dev.claudefleet.mobile.net.HUB_VERSION_KEYS
import dev.claudefleet.mobile.net.semverAtLeast

/**
 * One way to answer a blocked session, as data — never a raw keystroke typed
 * ad hoc. Task 3 turns one of these into a `send_prompt` call.
 */
sealed interface Answer {
    /**
     * A dialog's numbered option, pressed as the digit key [n] — never typed
     * as text (see [HUB_VERSION_DIGIT_KEYS] for why text cannot answer it).
     */
    data class Option(val n: Int, val label: String) : Answer
    data object Enter : Answer
    data object Escape : Answer

    // There was a `Text(text)` arm and an `Interrupt` arm here too, each with a
    // live handler in `SessionViewModel.answer` and a chip label, and neither
    // with a construction site anywhere outside one test: no card ever offered
    // either. `Text` was the last path by which this app would have typed text
    // into a blocked session — which the hub refuses, and which a dialog reads
    // as ESC first — so it is gone rather than left waiting for a caller.
    //
    // What is left is the invariant: EVERY answer is a key, so the pane
    // re-read in `answer` cannot be skipped by adding one. A new kind of
    // answer has to face that question deliberately.
}

/**
 * What Task 3 draws for a blocked or stuck session: a headline, the answers
 * to offer, an optional explanation, whether to offer a restart instead of
 * an answer, and whether the terminal is still available as a fallback.
 */
data class BlockedCard(
    val headline: String,
    val answers: List<Answer>,
    val explain: String? = null,
    val offerRestart: Boolean = false,
    val terminalAvailable: Boolean = true,
    /**
     * The dialog this card was BUILT from, carried so the pane re-read before
     * a key goes out can compare against what the person actually read.
     *
     * The guard used to take its "what was asked" operand from the live row at
     * tap time, which is the same row the probe is there to distrust: in the
     * window where a row event has landed but the chips have not recomposed
     * yet, both operands moved together and the refusal could not fire. The
     * card is the composed snapshot, so it cannot move under the tap.
     */
    val asked: PendingInput? = null,
    /** The row's `stuck_kind` when this card was built, for the same reason. */
    val askedStuck: String? = null,
    /**
     * The label of the option the REPL's own cursor is on, when it says.
     *
     * `PendingOption.selected` was decoded off the wire and read by nothing, so
     * the card never said which choice the pane was sitting on — and Enter,
     * drawn as a chip identical to the digits, was an unlabelled "press
     * whatever is highlighted". On a permission dialog that is the difference
     * between approving once and approving for good.
     */
    val highlighted: String? = null,
)

/**
 * The stuck-kind -> card mapping lives here and only here.
 *
 * Returns `null` when [row] is neither `blocked` nor stuck — nothing to show.
 * `hubVersion` gates the structured `send_prompt { keys }` chips: below
 * [HUB_VERSION_KEYS] (or when the hub named no version at all) the hub cannot
 * take a structured Enter/Escape, so those chips are withheld and the
 * terminal stays the only way to answer. The numbered options of a dialog
 * need [HUB_VERSION_DIGIT_KEYS] in the same way, and only `1`–`9` have a key:
 * the REPL has no keystroke for "10" that a dialog would not read as "1", so
 * a higher option is left to the terminal.
 */
fun blockedCard(row: SessionRow, hubVersion: String?): BlockedCard? =
    // One place stamps the identity, so a new arm cannot forget to.
    card(row, hubVersion)?.copy(
        asked = row.pendingInput,
        askedStuck = row.stuckKind,
        highlighted = row.pendingInput?.options
            ?.firstOrNull { it.selected }
            ?.label
            ?.takeIf { it.isNotBlank() },
    )

private fun card(row: SessionRow, hubVersion: String?): BlockedCard? {
    val stuck = row.stuckKind
    val blocked = row.claudeStatus == "blocked"
    if (stuck == null && !blocked) return null
    val keys = semverAtLeast(hubVersion, HUB_VERSION_KEYS)
    val keyAnswers = if (keys) listOf(Answer.Enter, Answer.Escape) else emptyList()
    return when (stuck) {
        "press_enter" -> BlockedCard("Press Enter to continue", if (keys) listOf(Answer.Enter) else emptyList())
        // Claude Code's trust dialog is a menu with "Yes" highlighted, not a
        // `(y/n)` line: Enter picks Yes, Esc exits. It has to be keys, too —
        // the hub refuses any typed text into a stuck session unless forced
        // (Enter would answer the dialog), and a client's text would carry
        // the untrusted-input marker line into the menu first.
        "trust_prompt" -> BlockedCard(
            "Trust this folder?",
            keyAnswers,
            explain = if (keys) "Enter trusts it, Esc exits Claude" else null,
        )
        "auth_menu" -> BlockedCard("Login needed", emptyList(), explain = "Needs a login on this host", offerRestart = true)
        "reconnect" -> BlockedCard("Reconnecting to Anthropic", emptyList(), explain = "The REPL lost its connection", offerRestart = true)
        "oom" -> BlockedCard("Out of memory", emptyList(), explain = "The host ran out of memory", offerRestart = true)
        null -> {
            val p = row.pendingInput
            val headline = p?.question ?: Activity.pending(row.currentActivity) ?: "Waiting for input"
            val offered = p?.options.orEmpty()
            val options = if (semverAtLeast(hubVersion, HUB_VERSION_DIGIT_KEYS)) {
                offered.takeIf { cleanOrdinals(it) }.orEmpty()
                    .filter { it.n in 1..ANSWER_MAX_DIGIT }
                    .map { Answer.Option(it.n, it.label) }
            } else {
                emptyList()
            }
            // A numbered dialog whose chips are ALL withheld — an old hub, an
            // option above 9, or a set whose ordinals repeat — drew as a bare
            // question with Enter and Esc and nothing saying the choices
            // existed, so the person had no way to know the answer was in the
            // terminal. Say it, and say which case it is.
            val withheld = offered.isNotEmpty() && options.isEmpty()
            BlockedCard(
                headline,
                options + keyAnswers,
                explain = if (withheld) whyNoChips(offered, hubVersion) else null,
            )
        }
        else -> BlockedCard(stuck.replace('_', ' '), keyAnswers, offerRestart = true)
    }
}

/** Highest option a single keystroke can pick — the hub's `DigitKey` range, `1`..`9`. */
internal const val ANSWER_MAX_DIGIT: Int = 9

/**
 * Whether these options are one menu: each ordinal once, in ascending order.
 *
 * A chip is pressed as the DIGIT, so the digit has to identify the option. An
 * older hub's pane parser can read a numbered list the AGENT printed as part
 * of the same block (a prose "1. … 2. …" above the dialog), which comes
 * through as `n = 1, 2, 1, 2, 3`: a chip labelled from the agent's prose,
 * pressing a digit the dialog reads as a different choice entirely. Nothing
 * here can tell which of two `1`s the REPL means, so the whole set is
 * withheld and the terminal stays the way to answer — the same conservative
 * call [blockedCard] makes for an option above [ANSWER_MAX_DIGIT].
 *
 * A GAP is not a repeat: `1, 3` is still one option per digit (the capture
 * can lose a choice line off its top), so those chips are drawn.
 */
internal fun cleanOrdinals(options: List<PendingOption>): Boolean =
    options.zipWithNext().all { (a, b) -> b.n > a.n }

/**
 * Why a numbered dialog is offering no option chips — in the words that tell a
 * person what to do about it, which is always "answer it in the terminal".
 */
private fun whyNoChips(offered: List<PendingOption>, hubVersion: String?): String = when {
    !semverAtLeast(hubVersion, HUB_VERSION_DIGIT_KEYS) ->
        "${offered.size} numbered choices — this hub is too old to press one; use the terminal"
    !cleanOrdinals(offered) ->
        "The choices came through numbered twice over, so pressing one could pick the wrong " +
            "answer — use the terminal"
    else ->
        "${offered.size} numbered choices, past the 9 a single key can press — use the terminal"
}
