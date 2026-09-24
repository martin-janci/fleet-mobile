package dev.claudefleet.mobile.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A session's link to a piece of work, as the hub stamps it on a session row
 * (`SessionRow.work` / `work_suggested`; `WorkSummary` in claude-fleet's
 * `store/work.rs`).
 *
 * Every field is defaulted: the hub skips an absent optional rather than
 * sending `null`, and fields were added across three milestones (M1b, M3, M4),
 * so a row from any of those hubs parses. The phone never derives a key
 * itself — this is the hub's decision, drawn as it is.
 */
@Serializable
data class WorkSummary(
    /** What `work_link { confirm | reject | unlink }` addresses. */
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("item_id") val itemId: Long? = null,
    /** The tracker's key, else the link's own reference (`PAY-7`, `owner/repo#42`). */
    val key: String? = null,
    /** Empty for a bare key the hub has no ticket for. */
    val title: String = "",
    /** manual | agent | branch | prompt | pr | trailer | … — kept as the hub's word. */
    val source: String = "",
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    /** The tracker's own status name ("In Review"). */
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    /** The tracker stopped answering for the item: draw it struck through. */
    val unavailable: Boolean = false,
    /** confirmed | suggested; empty from a hub older than M4. */
    val state: String = "",
    /** explicit | strong | weak. */
    val strength: String? = null,
    /** The hub's resolver rule that made the link (R3, R5, …). */
    val rule: String? = null,
    /** A suggestion the hub would pick: shown pre-selected. */
    val preselected: Boolean = false,
    /** How many suggestions the session still has to decide. */
    val suggestions: Int = 0,
) {
    /** What a chip says: the key, else the title. */
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title

    /** A guess no one has decided; never groups a session. */
    val isSuggestion: Boolean get() = state == "suggested"
}

/**
 * A tracker item's coarse status. [Unknown] is what a value this build has
 * never heard of reads as — a newer hub adding a category must not fail the
 * row it rides on, and an empty string (a local item) is not a status.
 */
@Serializable(with = StatusCategorySerializer::class)
enum class StatusCategory(val wire: String) {
    Todo("todo"),
    InProgress("in_progress"),
    Done("done"),
    Unknown("unknown"),
    ;

    companion object {
        fun of(wire: String?): StatusCategory = entries.firstOrNull { it.wire == wire && it != Unknown } ?: Unknown
    }
}

internal object StatusCategorySerializer : KSerializer<StatusCategory> {
    override val descriptor = PrimitiveSerialDescriptor("StatusCategory", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: StatusCategory) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): StatusCategory = StatusCategory.of(decoder.decodeString())
}

/**
 * One tracker item as `work { action: tickets | lookup }` answers it and as a
 * `work:item` frame carries it (claude-fleet's `Ticket`, which flattens
 * `WorkItemRow`).
 *
 * [liveSessionIds], [views] and [description] are NOT columns: `tickets` /
 * `lookup` add them, and a `work:item` frame, which is the bare row, cannot
 * carry them — see `FleetSnapshot.applying`.
 */
@Serializable
data class Ticket(
    val id: Long,
    val source: String = "",
    val key: String? = null,
    val title: String = "",
    val url: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory = StatusCategory.Unknown,
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("tracker_id") val trackerId: Long? = null,
    /** The tracker's type name (Story, Bug, …). */
    val kind: String? = null,
    val assignees: List<String> = emptyList(),
    /** The current sprint's name. */
    val iteration: String? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("unavailable_at") val unavailableAt: Long? = null,
    /** not_found_or_no_permission | tracker_removed. */
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    @SerialName("live_session_ids") val liveSessionIds: List<Long> = emptyList(),
    /** `lookup` only: the item's views (`mine`, `sprint`, `recent`, `filter:<id>`). */
    val views: List<String> = emptyList(),
    /** `lookup` only: a description excerpt. Third-party text. */
    val description: String? = null,
) {
    /** Missing is not gone: the tracker stopped answering for it (C25). */
    val unavailable: Boolean get() = unavailableAt != null || unavailableReason != null

    /** What a row says: the key, else the title. */
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title
}

/**
 * A tracker, from `work { action: trackers }` — only what the phone needs to
 * know whether one is connected and healthy. The hub never sends a secret here
 * (`TrackerRow` cannot carry one).
 */
@Serializable
data class TrackerSummary(
    val id: Long,
    val provider: String = "",
    val name: String = "",
    /** ok | auth_failed | rate_limited | unreachable | …; anything but `ok` is not ok. */
    val state: String = "",
) {
    val ok: Boolean get() = state == "ok"
}

/**
 * What resuming a key would do — `work { action: resume_plan }`.
 *
 * [modes] is required on purpose, as it is on the hub: an older hub answers
 * this action with its plain link list, which must fail to parse rather than
 * pass for an empty plan.
 */
@Serializable
data class ResumePlan(
    val key: String,
    val title: String? = null,
    /** Sessions already on this key: the phone offers Jump, never a second session. */
    val live: List<LiveWork> = emptyList(),
    val candidates: List<ResumeCandidate> = emptyList(),
    @SerialName("link_id") val linkId: Long? = null,
    @SerialName("host_alias") val hostAlias: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
    val branch: String? = null,
    val worktree: String? = null,
    val modes: List<ResumeMode>,
    /** Reachable hosts, for the host picker. */
    val hosts: List<String> = emptyList(),
) {
    /** Whether [mode] is possible for the plan's candidate. */
    fun can(mode: String): Boolean = modes.any { it.mode == mode && it.ok }
}

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
    @SerialName("ended_at") val endedAt: Long? = null,
    val name: String? = null,
    @SerialName("host_alias") val hostAlias: String? = null,
    val branch: String? = null,
    val resumable: Boolean = true,
)

@Serializable
data class ResumeMode(
    /** last | brief | fresh. */
    val mode: String,
    val ok: Boolean = false,
    val reason: String? = null,
)
