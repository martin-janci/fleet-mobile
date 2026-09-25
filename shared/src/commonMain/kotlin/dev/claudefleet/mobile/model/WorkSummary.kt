package dev.claudefleet.mobile.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a session is working on, as the hub stamps it on the row: `work` (the
 * primary link) and `work_suggested` (the top guess nobody has decided yet).
 *
 * Mirrors `WorkSummary` in claude-fleet's `crates/fleet-core/src/store/work.rs`.
 * Every field is defaulted because the hub skips the optional ones when they
 * are empty — a bare key has no status, a link older than M4 has no `state` —
 * and a phone must read a row from any hub that has the work graph at all.
 *
 * The phone never derives a key itself: whatever the hub stamped here is the
 * answer, and a row without it sits in its project group (hybrid grouping).
 */
@Serializable
data class WorkSummary(
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("item_id") val itemId: Long? = null,
    /** The item's key, else the link's own `ref_key`. */
    val key: String? = null,
    /** The ticket's title — third-party text, drawn as plain text only. */
    val title: String = "",
    /** How the link was made: `manual`, `agent`, `branch`, `started`, … */
    val source: String = "",
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    /** The tracker's own status name ("In Review"). */
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    /** The tracker no longer answers for the item. */
    val unavailable: Boolean = false,
    /** `confirmed` | `suggested`; empty from a hub older than M4. */
    val state: String = "",
    /** `explicit` | `strong` | `weak`. */
    val strength: String? = null,
    /** The resolver rule that made the link (R3, R5 …). */
    val rule: String? = null,
    val preselected: Boolean = false,
    /** Live suggestions the session still has to decide. */
    val suggestions: Int = 0,
) {
    /** What a chip says: the key, else the title, else nothing worth drawing. */
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title

    /**
     * The grouping identity — the key, compared case-insensitively the way the
     * hub normalises ticket-shaped keys — or null for a link with neither key
     * nor title.
     */
    val groupKey: String? get() = label.takeIf { it.isNotBlank() }?.uppercase()
}

/**
 * A tracker item's status bucket. [Unknown] is what a value a later hub adds
 * reads as — a new wire enum must never fail the row it rides on.
 */
@Serializable(with = StatusCategorySerializer::class)
enum class StatusCategory(val wire: String) {
    Todo("todo"),
    InProgress("in_progress"),
    Done("done"),
    Unknown(""),
    ;

    companion object {
        fun of(wire: String?): StatusCategory? = when {
            wire == null -> null
            else -> entries.firstOrNull { it != Unknown && it.wire == wire } ?: Unknown
        }
    }
}

internal object StatusCategorySerializer : KSerializer<StatusCategory> {
    override val descriptor = PrimitiveSerialDescriptor("StatusCategory", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: StatusCategory) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): StatusCategory =
        StatusCategory.of(decoder.decodeString()) ?: StatusCategory.Unknown
}

/**
 * One tracker ticket, as `work { action: tickets | lookup }` answers it and as
 * a `work:item` frame carries it: the hub's `WorkItemRow` (flattened), plus
 * the live sessions already on it.
 *
 * Only the fields a `work:item` frame can carry are modelled besides
 * [liveSessionIds], which the frame cannot — so an upsert keeps it from the
 * copy it replaces, the same trap `is_controller` sprang on the session row.
 */
@Serializable
data class Ticket(
    /** The work item id (`item_id` on a session's [WorkSummary]). */
    val id: Long,
    val key: String? = null,
    /** Third-party text: drawn as plain text only. */
    val title: String = "",
    val url: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("tracker_id") val trackerId: Long? = null,
    val iteration: String? = null,
    @SerialName("unavailable_at") val unavailableAt: Long? = null,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    @SerialName("updated_ext") val updatedExt: Long? = null,
    @SerialName("live_session_ids") val liveSessionIds: List<Long> = emptyList(),
    /** `lookup` only: an excerpt. Third-party text: plain text only. */
    val description: String? = null,
    /** `lookup` only: `mine`, `sprint`, `recent`, `filter:<id>`. */
    val views: List<String> = emptyList(),
) {
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title
    /** The tracker stopped answering for it — or, from a `work:tracker_removed` frame, the tracker is gone. */
    val unavailable: Boolean get() = unavailableAt != null || unavailableReason != null
}

/** A connected tracker, as `work { action: trackers }` lists it — no secrets. */
@Serializable
data class TrackerRow(
    val id: Long,
    val provider: String = "",
    val name: String = "",
    /** `ok` or a reason it is not; an unknown value reads as "not ok". */
    val state: String = "",
)

/**
 * What resuming a key would do — `work { action: resume_plan }`.
 *
 * `modes` is required, mirroring the hub: an older hub answers the same call
 * with a plain link list, which then fails to parse instead of passing for an
 * empty plan, and the phone hides Resume.
 */
@Serializable
data class ResumePlan(
    val key: String,
    val title: String? = null,
    val live: List<LiveWork> = emptyList(),
    val candidates: List<ResumeCandidate> = emptyList(),
    @SerialName("host_alias") val hostAlias: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
    val modes: List<ResumeMode>,
    /** Reachable hosts, for a host override. */
    val hosts: List<String> = emptyList(),
) {
    /** The `last` mode, when the hub allows it — the only mode the phone offers. */
    val canResumeLast: Boolean get() = modes.any { it.mode == "last" && it.ok }
}

@Serializable
data class ResumeMode(val mode: String, val ok: Boolean = false, val reason: String? = null)

@Serializable
data class LiveWork(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("tmux_name") val tmuxName: String = "",
    @SerialName("friendly_name") val friendlyName: String? = null,
)

@Serializable
data class ResumeCandidate(
    @SerialName("link_id") val linkId: Long,
    @SerialName("host_alias") val hostAlias: String? = null,
    val resumable: Boolean = true,
)

/**
 * This link as the ticket cache knows it now: [ticket]'s title, status and
 * availability over the ones stamped on the session row.
 *
 * A `work:item` frame updates the cache but does not restamp the session
 * rows that link the item, so without this a work heading and a row's chip
 * keep the status they had when the session last changed. The cache is the
 * newer word because it is emptied on every re-list and then only moves on
 * frames (see `FleetRepository.relist`). An empty ticket title keeps the
 * row's — a bare key has none to give.
 */
fun WorkSummary.refreshedBy(ticket: Ticket?): WorkSummary {
    if (ticket == null || ticket.id != itemId) return this
    return copy(
        title = ticket.title.ifBlank { title },
        statusCategory = ticket.statusCategory ?: statusCategory,
        statusName = ticket.statusName ?: statusName,
        url = ticket.url ?: url,
        unavailable = ticket.unavailable,
    )
}

/** [work] and [workSuggested] refreshed from the ticket cache; this very row when nothing changes. */
fun SessionRow.withTicketsFrom(tickets: Map<Long, Ticket>): SessionRow {
    if (tickets.isEmpty()) return this
    val w = work?.let { it.refreshedBy(it.itemId?.let(tickets::get)) }
    val g = workSuggested?.let { it.refreshedBy(it.itemId?.let(tickets::get)) }
    return if (w == work && g == workSuggested) this else copy(work = w, workSuggested = g)
}
