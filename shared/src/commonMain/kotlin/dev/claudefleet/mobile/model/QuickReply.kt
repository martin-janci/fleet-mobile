package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
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
 *
 * [autoSend] is the chip's own answer to "what does a tap do": true sends the
 * prompt at once, false only puts it in the composer to be edited first. The
 * hub serves it on every chip; null means a hub from before the flag existed
 * (or a chip cached by a build from before it), and such a chip keeps doing
 * what every chip on this app used to do — send. Null is also never written:
 * the client's `explicitNulls = false` leaves the key out, and a hub reading
 * an entry without it keeps the flag the stored chip already has.
 */
@Serializable
data class QuickReply(
    val label: String,
    val text: String,
    @SerialName("auto_send") val autoSend: Boolean? = null,
) {
    /** What the chip shows: the label, falling back to the prompt itself. */
    val caption: String get() = label.ifBlank { text }

    /** Whether a tap sends the prompt (true) or only fills the composer. */
    val sendsOnTap: Boolean get() = autoSend ?: true

    companion object {
        /**
         * A chip with no label of its own — a plain string, this app's old
         * shape. Its [autoSend] is left to the hub (null), which keeps the
         * flag a chip with the same prompt already has, else off.
         */
        fun of(text: String) = QuickReply(label = text.trim(), text = text.trim())
    }
}
