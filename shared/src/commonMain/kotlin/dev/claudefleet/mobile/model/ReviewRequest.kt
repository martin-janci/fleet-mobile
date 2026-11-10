package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A review a colleague asked of this person (claude-fleet "Ask for review…",
 * migration 225, `store::ReviewRequestRow`): the session is shared with them
 * and the ask sits in their Inbox until they mark it done.
 */
@Serializable
data class ReviewRequest(
    val id: Long,
    @SerialName("session_id") val sessionId: Long,
    @SerialName("from_person_id") val fromPersonId: Long = 0,
    /** Who asked, as the hub names them. */
    @SerialName("from_name") val fromName: String = "",
    @SerialName("to_person_id") val toPersonId: Long = 0,
    @SerialName("to_name") val toName: String = "",
    /** The share it came with: drive (may send notes to the agent) or watch (reads and drafts). */
    val level: String = "watch",
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("done_at") val doneAt: Long? = null,
)

/** The line under an ask in the Inbox: who asked and what this person may do. */
fun reviewAskLine(r: ReviewRequest): String {
    val who = r.fromName.ifBlank { "A colleague" }
    val may = if (r.level == "drive") "you can send notes" else "you can read and draft"
    return "$who asks you to review · $may"
}
