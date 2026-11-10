package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A note on a line of a session's changes, already sent to its agent
 * (claude-fleet review notes, migration 223, `store::ReviewNoteRow`).
 */
@Serializable
data class ReviewNote(
    val id: Long,
    @SerialName("session_id") val sessionId: Long = 0,
    /** The send's `client_msg_id`. */
    val batch: String = "",
    /** Its number in that send, as the prompt numbers it. */
    val seq: Long = 0,
    val path: String,
    val line: Long? = null,
    /** `new` (an added or unchanged line) or `old` (a removed one). */
    val side: String = NoteSide.NEW,
    val quote: String? = null,
    val body: String,
    /** Who sent it ("Ada · phone"). */
    val author: String = "",
    @SerialName("author_person_id") val authorPersonId: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("resolved_at") val resolvedAt: Long? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
) {
    val resolved: Boolean get() = resolvedAt != null
}

/** What `review_notes { action: send }` answered. */
@Serializable
data class ReviewNotesSent(
    @SerialName("session_id") val sessionId: Long = 0,
    val notes: List<ReviewNote> = emptyList(),
    /** Typed into the pane now; false when the session was busy and it was queued. */
    val delivered: Boolean = false,
    @SerialName("queued_id") val queuedId: Long? = null,
    /** The hub had this send already (a retry): it typed nothing a second time. */
    val repeat: Boolean = false,
)

object NoteSide {
    const val NEW = "new"
    const val OLD = "old"
}

/** Where a note sits: a file, a line and the side of the diff that line is on. */
data class NoteAnchor(val path: String, val line: Long, val side: String)

/** A note written on this phone and not sent yet. Drafts never leave the device until Send. */
data class NoteDraft(val anchor: NoteAnchor, val quote: String, val body: String)

/** The most a send may carry and the most one note may say, as the hub enforces them. */
const val REVIEW_NOTES_MAX = 50
const val REVIEW_NOTE_BODY_MAX = 4000

/**
 * The anchor of a diff line: an added or unchanged line takes the new side, a
 * removed line the old one, as the desktop's diff does. Null for a hunk header
 * or file metadata, which have no line to note.
 */
fun noteAnchor(path: String, old: Int?, new: Int?, removed: Boolean): NoteAnchor? = when {
    removed && old != null -> NoteAnchor(path, old.toLong(), NoteSide.OLD)
    !removed && new != null -> NoteAnchor(path, new.toLong(), NoteSide.NEW)
    else -> null
}

/** The anchor a sent note sits on, or null for a note on the whole file. */
fun ReviewNote.anchor(): NoteAnchor? = line?.let { NoteAnchor(path, it, side) }

/** The line as the note quotes it: without the diff's `+`/`-`/space column. */
fun noteQuote(diffText: String): String =
    if (diffText.isNotEmpty() && diffText[0] in "+- ") diffText.substring(1) else diffText

/** The label of the send button: "Send 3 notes to Claude". */
fun sendNotesLabel(count: Int, agent: String): String =
    "Send $count ${if (count == 1) "note" else "notes"} to $agent"

/**
 * An id for one send (`client_msg_id`): kept until the send is answered, so a
 * retry after a dropped answer types nothing twice.
 */
fun newNotesSendId(nowSeconds: Long, random: kotlin.random.Random = kotlin.random.Random.Default): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    val tail = buildString { repeat(10) { append(alphabet[random.nextInt(alphabet.length)]) } }
    return "rn-${nowSeconds.coerceAtLeast(0).toString(36)}-$tail"
}

/**
 * `review_notes { action: send }`'s arguments: the drafts in file and line
 * order, each with its quoted line; a blank quote is left out.
 */
fun reviewNotesSendArgs(sessionId: Long, clientMsgId: String, drafts: List<NoteDraft>): JsonObject = buildJsonObject {
    put("session_id", sessionId)
    put("action", "send")
    put("client_msg_id", clientMsgId)
    putJsonArray("notes") {
        for (d in orderedDrafts(drafts)) {
            addJsonObject {
                put("path", d.anchor.path)
                put("line", d.anchor.line)
                put("side", d.anchor.side)
                if (d.quote.isNotBlank()) put("quote", d.quote)
                put("body", d.body.trim())
            }
        }
    }
}

/** Drafts as the message numbers them: by file, then line, the old side first. */
fun orderedDrafts(drafts: Collection<NoteDraft>): List<NoteDraft> =
    drafts.sortedWith(compareBy({ it.anchor.path }, { it.anchor.line }, { if (it.anchor.side == NoteSide.OLD) 0 else 1 }))
