package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Activity
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
    data class Option(val n: Int, val label: String, val checked: Boolean = false) : Answer
    data object Enter : Answer

    /**
     * `Tab` on a multi-select question: keeps the ticks and moves on (to the
     * next question, or to the review step's `1. Submit answers`). A digit
     * there only TOGGLES a box, so without this the phone could never finish
     * one.
     */
    data object Continue : Answer
    data object Escape : Answer
    data object Interrupt : Answer
    data class Text(val text: String) : Answer

    /**
     * Pressed as one of the keys an Answer share may press (an option's
     * digit, Enter, Escape) rather than typed or interrupting: what tells an
     * answer a person shared at answer may give from one that needs drive.
     */
    val isKey: Boolean get() = this is Option || this == Enter || this == Escape || this == Continue
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
    /** The tool call a permission dialog asks about (`Bash(git push)`), as the hub read it off the pane. */
    val detail: String? = null,
    /** A multi-select question: an option's chip toggles its box, [Answer.Continue] moves on. */
    val multi: Boolean = false,
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
/** The newest call still waiting for its result: what a permission dialog is asking about. */
internal fun pendingTool(turns: List<dev.claudefleet.mobile.model.ConvTurn>): dev.claudefleet.mobile.model.ConvItem.Tool? =
    turns.lastOrNull()?.items?.lastOrNull { it is dev.claudefleet.mobile.model.ConvItem.Tool && !it.done }
        as? dev.claudefleet.mobile.model.ConvItem.Tool

fun blockedCard(row: SessionRow, hubVersion: String?): BlockedCard? {
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
            val digits = semverAtLeast(hubVersion, HUB_VERSION_DIGIT_KEYS)
            val options = if (digits) {
                p?.options.orEmpty().filter { it.n in 1..ANSWER_MAX_DIGIT }.map { Answer.Option(it.n, it.label, it.checked) }
            } else {
                emptyList()
            }
            val multi = p?.multi == true
            val continueKey = if (multi && digits) listOf(Answer.Continue) else emptyList()
            BlockedCard(headline, options + continueKey + keyAnswers, detail = p?.detail, multi = multi)
        }
        else -> BlockedCard(stuck.replace('_', ' '), keyAnswers, offerRestart = true)
    }
}

/** Highest option a single keystroke can pick — the hub's `DigitKey` range, `1`..`9`. */
internal const val ANSWER_MAX_DIGIT: Int = 9
