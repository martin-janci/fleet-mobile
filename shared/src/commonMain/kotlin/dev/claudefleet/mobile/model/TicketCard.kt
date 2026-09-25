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
 * [composerText] is what **Insert into composer** puts in a session's draft
 * (M9.2): the ticket's line and link, then its criteria or excerpt inside
 * the hub's untrusted fence. It is only ever put in the draft, never sent.
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
    @SerialName("composer_text") val composerText: String = "",
) {
    /** Whether the card has anything to say beyond the key and title. */
    val hasBody: Boolean get() = acceptance.isNotEmpty() || !excerpt.isNullOrBlank()
}
