package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sharing a session (claude-fleet multi-user M1 and Orbit Fleet 11.7), as the
 * phone reads and writes it (redesign 11.10).
 *
 * The hub decides every one of these: a share, a revoke and a narrow are the
 * owner's (`Reach::Own`), and what a grantee may do with a shared session is
 * enforced by the tool it calls. What lives here is how the phone *reads*
 * that, so it can say why a box is dark rather than letting a tap be refused.
 */

/** The three grantable levels, narrowest first. "own" is not one: it is never granted. */
object GrantLevel {
    const val WATCH = "watch"
    const val ANSWER = "answer"
    const val DRIVE = "drive"

    /** Every level a share may be made at, narrowest first. */
    val ALL: List<String> = listOf(WATCH, ANSWER, DRIVE)

    /** The capitalised word a level is drawn as. */
    fun word(level: String): String = when (level) {
        WATCH -> "Watch"
        ANSWER -> "Answer"
        DRIVE -> "Drive"
        else -> level.replaceFirstChar { it.uppercase() }
    }

    /** What each level lets the person do, as the share sheet offers it (the desktop's words). */
    fun detail(level: String): String = when (level) {
        WATCH -> "read only"
        ANSWER -> "can answer its questions"
        DRIVE -> "can send prompts"
        else -> ""
    }
}

/** One entry of `my_grants`: a session shared with this person, and at what level. */
@Serializable
data class MyGrant(
    @SerialName("session_id") val sessionId: Long,
    val level: String,
)

/** `my_grants`: who this device's person is on the fleet, and every live grant to them. */
@Serializable
data class MyGrants(
    @SerialName("person_id") val personId: Long? = null,
    val grants: List<MyGrant> = emptyList(),
)

/**
 * What this phone may do with each session, derived the way the desktop's
 * `access.ts::sessionAccess` derives it: the row's `owner_person_id` against
 * this person, else the level a grant names.
 *
 * Only ever a *narrowing*. [levelFor] answers null — "nothing to say, let the
 * hub decide" — for a row this phone owns, for an unclaimed row, and for any
 * hub that has not said who this device is ([UNKNOWN]). A phone talking to a
 * hub without sharing therefore behaves exactly as before.
 */
data class MyAccess(
    val personId: Long? = null,
    val grants: Map<Long, String> = emptyMap(),
) {
    /** The level [row] is shared with this person at, or null when it is theirs or nothing is known. */
    fun levelFor(row: SessionRow?): String? {
        if (row == null || personId == null) return null
        if (row.ownerPersonId != null && row.ownerPersonId == personId) return null
        return grants[row.id]
    }

    /** This person owns [row]: the share sheet is theirs to open. */
    fun owns(row: SessionRow?): Boolean =
        row != null && personId != null && row.ownerPersonId != null && row.ownerPersonId == personId

    companion object {
        val UNKNOWN = MyAccess()

        fun of(answer: MyGrants): MyAccess =
            MyAccess(answer.personId, answer.grants.associate { it.sessionId to it.level })
    }
}

/**
 * One live grant on a session, as `session_access` answers it — a person or
 * an org, the level, who granted it and when. Names are optional on the wire,
 * so [recipient] can be null; such a grant is listed but cannot be acted on.
 */
@Serializable
data class SessionGrant(
    @SerialName("session_id") val sessionId: Long = 0,
    @SerialName("person_id") val personId: Long? = null,
    @SerialName("person_name") val personName: String? = null,
    @SerialName("person_display_name") val personDisplayName: String? = null,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("org_name") val orgName: String? = null,
    val level: String = "",
    @SerialName("granted_by") val grantedBy: Long = 0,
    @SerialName("granted_at") val grantedAt: Long = 0,
) {
    /** Who to send back to the sharing tools: the hub takes names, not ids. */
    val recipient: ShareTo?
        get() = if (orgId != null) {
            orgName?.takeIf { it.isNotBlank() }?.let { ShareTo.Org(it) }
        } else {
            personName?.takeIf { it.isNotBlank() }?.let { ShareTo.Person(it) }
        }

    /** "Silvester", "32bit (its members)", or the id when the hub named no one. */
    val label: String
        get() = if (orgId != null) {
            "${orgName?.takeIf { it.isNotBlank() } ?: "org #$orgId"} (its members)"
        } else {
            personDisplayName?.takeIf { it.isNotBlank() }
                ?: personName?.takeIf { it.isNotBlank() }
                ?: personId?.let { "person #$it" }
                ?: "unknown"
        }

    /** A share above watch can be narrowed to it; nothing raises one. */
    val narrowable: Boolean get() = level == GrantLevel.DRIVE || level == GrantLevel.ANSWER
}

/** Who a share is addressed to: a person by name, or an org by name (its members as of now). */
sealed interface ShareTo {
    data class Person(val name: String) : ShareTo
    data class Org(val name: String) : ShareTo
}

/**
 * What a session shared with this person says on its screen, in the
 * desktop's words (`access.ts::noAttachReason` without the terminal clause,
 * which a phone that never attaches has no use for).
 */
fun sharedSentence(level: String): String = when (level) {
    GrantLevel.ANSWER ->
        "Shared with you to answer. You can answer the questions it asks; sending a prompt needs drive, which only its owner can grant."
    GrantLevel.DRIVE ->
        "Shared with you to drive. You can send it prompts; renaming, moving, stopping or sharing it stays with its owner."
    else ->
        "Shared with you to watch. Watch is read-only: answering its questions needs answer, and sending a prompt needs drive, which only its owner can grant."
}
