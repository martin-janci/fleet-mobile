package dev.claudefleet.mobile.model

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
