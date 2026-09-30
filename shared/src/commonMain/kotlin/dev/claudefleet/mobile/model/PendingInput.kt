package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One selectable choice in a `pending_input` prompt, as the hub's REPL parser
 * read it off the pane: `n` is the number a person would type, `label` is the
 * option's text, and `selected` marks the REPL's own default cursor.
 */
@Serializable
data class PendingOption(val n: Int, val label: String, val selected: Boolean = false)

/**
 * The hub's structured reading of a blocked session's prompt (the row's
 * `pending_input`). `kind` is `"permission"` or `"input"`; `question` is the
 * prompt text when the hub could isolate it, else `null`; `options` lists the
 * numbered choices the REPL is offering, in the order it drew them, or is
 * empty for a free-text prompt.
 */
@Serializable
data class PendingInput(
    val kind: String,
    val question: String? = null,
    val options: List<PendingOption> = emptyList(),
)

/**
 * A stable identity for "the question being asked" — the desktop's
 * `answerFingerprint`, so both clients decide "is it still the same dialog"
 * the same way. Which option is *highlighted* is left out on purpose: an
 * arrow key moves the cursor without changing the question.
 */
fun PendingInput.fingerprint(): List<Any?> =
    listOf(kind, question, options.map { it.n to it.label })

/**
 * What `session_activity` answers: one `capture-pane` read of the session's
 * pane, seconds old rather than up to a reconcile tick old like the row.
 * [pendingInput] is the dialog on screen right now. The hub also sends
 * `current_activity`, `waiting_for` and `spinner`, left off here because the
 * only thing this app reads the probe for is the check made immediately
 * before a dialog answer goes out.
 */
@Serializable
data class ActivityProbe(
    @SerialName("claude_status") val claudeStatus: String? = null,
    @SerialName("stuck_kind") val stuckKind: String? = null,
    @SerialName("pending_input") val pendingInput: PendingInput? = null,
)
