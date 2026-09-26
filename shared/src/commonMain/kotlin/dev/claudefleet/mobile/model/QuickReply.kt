package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

/**
 * One chip in the composer's quick-reply row, as the hub's `quick_replies`
 * tool reports it: [label] is what the button says, [text] what tapping it
 * sends.
 *
 * The two are separate because a useful prompt is usually too long to fit on a
 * chip ("Review the work in this worktree. Run `git diff` …" under a chip that
 * says *Review*). A chip whose label adds nothing simply repeats the text —
 * which is exactly what this app wrote for every chip before the list moved to
 * the hub, when a chip was a bare string — so [of] is the shape that migration
 * takes, and [caption] is what a row draws either way.
 */
@Serializable
data class QuickReply(
    val label: String,
    val text: String,
) {
    /** What the chip shows: the label, falling back to the prompt itself. */
    val caption: String get() = label.ifBlank { text }

    companion object {
        /** A chip with no label of its own — a plain string, this app's old shape. */
        fun of(text: String) = QuickReply(label = text.trim(), text = text.trim())
    }
}
