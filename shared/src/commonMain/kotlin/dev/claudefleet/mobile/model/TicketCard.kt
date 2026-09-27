package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A ticket's context card, `work { action: card }` (claude-fleet M9.2): the
 * acceptance criteria parsed from the hub's cached description, or an
 * excerpt when it has none. Read from the hub's cache only — the hub never
 * calls the tracker for it — so a key nobody cached answers with
 * [cached] false and nothing else to show.
 *
 * Every string is the tracker's text: drawn as plain text, never as markup.
 * The hub's `composer_text` (what the desktop inserts into its composer) is
 * not read: the phone does not type a ticket into a prompt.
 */
@Serializable
data class TicketCard(
    val key: String,
    val title: String = "",
    val url: String? = null,
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("org_id") val orgId: Long? = null,
    val cached: Boolean = false,
    val acceptance: List<String> = emptyList(),
    val excerpt: String? = null,
) {
    /** Whether the card has anything to say beyond the key and title. */
    val hasBody: Boolean get() = acceptance.isNotEmpty() || !excerpt.isNullOrBlank()

    /**
     * What **Copy** puts on the clipboard (claude-fleet M10.5): the key and
     * title, the status, the link, then the acceptance criteria — or the
     * excerpt when there are none. Plain text, the tracker's words as they
     * are; the phone copies a card and never sends one.
     *
     * The link is only included when it is `http(s)`, the same rule the
     * sheets' *Open in browser* follows.
     */
    val copyText: String
        get() = buildString {
            append(key)
            if (title.isNotBlank()) append(" · ").append(title.trim())
            statusName?.takeIf { it.isNotBlank() }?.let { append("\nStatus: ").append(it.trim()) }
            url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { append('\n').append(it) }
            val criteria = acceptance.map { it.trim() }.filter { it.isNotEmpty() }
            when {
                criteria.isNotEmpty() -> {
                    append("\n\nAcceptance criteria")
                    for (line in criteria) append("\n- ").append(line)
                }
                !excerpt.isNullOrBlank() -> append("\n\n").append(excerpt.trim())
            }
        }
}
