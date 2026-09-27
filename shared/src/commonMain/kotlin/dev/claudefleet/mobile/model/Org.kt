package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One organisation as `work { action: orgs }` lists it (claude-fleet M5's
 * `OrgDetail`): the name and colour a label draws, and the trackers whose
 * tickets belong to it. The hub also sends the org's rules and hosts, which
 * the phone does not read.
 */
@Serializable
data class OrgDetail(
    val id: Long,
    val name: String = "",
    val color: String? = null,
    val trackers: List<OrgTracker> = emptyList(),
)

@Serializable
data class OrgTracker(val id: Long, val name: String = "")

/** An org as a label draws it. */
data class OrgInfo(val id: Long, val name: String, val color: String? = null)

/**
 * The orgs this token can see, read once per connection: what an org id on a
 * row, a work link, a Today entry or a ticket's tracker is called.
 *
 * A paired phone's token is never org-scoped on the hub (`OrgScope::All`), so
 * everything here is a way of *reading* the fleet — a label, a filter — and
 * never a fence.
 */
data class OrgDirectory(
    val orgs: Map<Long, OrgInfo> = emptyMap(),
    /** Tracker id → org id, from each org's tracker list. */
    val trackerOrg: Map<Long, Long> = emptyMap(),
) {
    /** What [id] is called; an id this directory has not heard of still gets a label. */
    fun name(id: Long): String = orgs[id]?.name?.takeIf { it.isNotBlank() } ?: "Org $id"

    /** The org a ticket belongs to: its tracker's. */
    fun orgOf(ticket: Ticket): Long? = ticket.trackerId?.let(trackerOrg::get)

    companion object {
        val EMPTY = OrgDirectory()

        fun of(details: List<OrgDetail>) = OrgDirectory(
            orgs = details.associate { it.id to OrgInfo(it.id, it.name, it.color) },
            trackerOrg = details.flatMap { d -> d.trackers.map { it.id to d.id } }.toMap(),
        )
    }
}

/**
 * The org a session belongs to: the hub's own `org_id` on the row, else its
 * work's. The second stands in for a hub older than claude-fleet's M8.6,
 * whose phone view leaves `org_id` out — its work links still carry one.
 */
val SessionRow.orgOf: Long? get() = orgId ?: work?.orgId

/**
 * An org's colour as the desktop stores it (`#rgb` or `#rrggbb`, claude-fleet
 * M5), as an opaque ARGB value for the row's colour bar; null for anything
 * else, which draws no bar rather than a guessed one.
 */
fun orgColorArgb(hex: String?): Long? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    if (digits.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
    val rgb = when (digits.length) {
        3 -> digits.map { "$it$it" }.joinToString("")
        6 -> digits
        else -> return null
    }
    return 0xFF000000L or rgb.toLong(16)
}
