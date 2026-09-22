package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Activity
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HUB_VERSION_KEYS
import dev.claudefleet.mobile.net.semverAtLeast

/**
 * One way to answer a blocked session, as data — never a raw keystroke typed
 * ad hoc. Task 3 turns one of these into a `send_prompt` call.
 */
sealed interface Answer {
    data class Option(val n: Int, val label: String) : Answer
    data object Enter : Answer
    data object Escape : Answer
    data object Interrupt : Answer
    data class Text(val text: String) : Answer
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
)

/**
 * The stuck-kind -> card mapping lives here and only here.
 *
 * Returns `null` when [row] is neither `blocked` nor stuck — nothing to show.
 * `hubVersion` gates the structured `send_prompt { keys }` chips: below
 * [HUB_VERSION_KEYS] (or when the hub named no version at all) the hub cannot
 * take a structured Enter/Escape, so those chips are withheld and the
 * terminal stays the only way to answer.
 */
fun blockedCard(row: SessionRow, hubVersion: String?): BlockedCard? {
    val stuck = row.stuckKind
    val blocked = row.claudeStatus == "blocked"
    if (stuck == null && !blocked) return null
    val keys = semverAtLeast(hubVersion, HUB_VERSION_KEYS)
    val keyAnswers = if (keys) listOf(Answer.Enter, Answer.Escape) else emptyList()
    return when (stuck) {
        "press_enter" -> BlockedCard("Press Enter to continue", if (keys) listOf(Answer.Enter) else emptyList())
        "trust_prompt" -> BlockedCard("Trust this folder?", listOf(Answer.Text("y"), Answer.Text("n")))
        "auth_menu" -> BlockedCard("Login needed", emptyList(), explain = "Needs a login on this host", offerRestart = true)
        "reconnect" -> BlockedCard("Reconnecting to Anthropic", emptyList(), explain = "The REPL lost its connection", offerRestart = true)
        "oom" -> BlockedCard("Out of memory", emptyList(), explain = "The host ran out of memory", offerRestart = true)
        null -> {
            val p = row.pendingInput
            val headline = p?.question ?: Activity.pending(row.currentActivity) ?: "Waiting for input"
            val options = p?.options.orEmpty().map { Answer.Option(it.n, it.label) }
            BlockedCard(headline, options + keyAnswers)
        }
        else -> BlockedCard(stuck.replace('_', ' '), keyAnswers, offerRestart = true)
    }
}
