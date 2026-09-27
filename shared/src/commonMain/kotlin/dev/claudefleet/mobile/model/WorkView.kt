package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * The Work view (claude-fleet work graph M14): the other way into the work
 * graph — org → group → task → every session of the task. Mirrors the hub's
 * `service/work/view.rs` wire types, with the fields the phone reads; a field
 * a newer hub adds is ignored (`ignoreUnknownKeys`), and a field the hub left
 * out (`skip_serializing_if`) is its default here.
 *
 * Every title, name, status name and "why" is text from a tracker or a
 * session, drawn as plain text only.
 */

/** A link's state on the wire: `active`, `ended`, `suggested` or `rejected`. */
object LinkState {
    const val ACTIVE = "active"
    const val ENDED = "ended"
    const val SUGGESTED = "suggested"
    const val REJECTED = "rejected"
}

/** Where a task sits and why (`GroupRef`). Navigation only, never a boundary. */
@Serializable
data class GroupRef(
    /** `label:<label>`, `tracker:<id>:<container>`, `repo:<owner/repo>`, `key:<PREFIX>`, `none`. */
    val id: String = "none",
    val label: String = "",
    /** `manual` | `rule` | `tracker` | `repo` | `key` | `none`. */
    val source: String = "none",
)

/** One session under a task (`TaskLink`). */
@Serializable
data class TaskLink(
    @SerialName("link_id") val linkId: Long,
    @SerialName("link_version") val linkVersion: Long = 0,
    /** [LinkState]; an unknown word from a newer hub is shown as it is. */
    val state: String = "",
    val primary: Boolean = false,
    /** The live session; null once the link or the session has ended. */
    @SerialName("session_id") val sessionId: Long? = null,
    val name: String = "",
    val host: String? = null,
    val source: String = "",
    val strength: String? = null,
    val rule: String? = null,
    /** One line: why this link exists. */
    val why: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("claude_status") val claudeStatus: String? = null,
    @SerialName("needs_you") val needsYou: Boolean = false,
    val archived: Boolean = false,
    val resumable: Boolean = false,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("cross_org") val crossOrg: Boolean = false,
    @SerialName("other_tasks") val otherTasks: Int = 0,
)

@Serializable
data class TaskCounts(
    val active: Int = 0,
    val ended: Int = 0,
    val suggested: Int = 0,
)

/** One task of the Work view (`WorkTask`). */
@Serializable
data class WorkTask(
    /** `item:<id>` or `ref:<KEY>` — the identity; a title never is. */
    @SerialName("task_id") val taskId: String,
    @SerialName("item_id") val itemId: Long? = null,
    val key: String? = null,
    val title: String = "",
    val url: String? = null,
    /** `tracker` | `local` | `ref`. */
    val kind: String = "",
    @SerialName("tracker_id") val trackerId: Long? = null,
    @SerialName("tracker_name") val trackerName: String? = null,
    /** The tracker's sync state: an outage is not "no sessions". */
    @SerialName("tracker_state") val trackerState: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    val unavailable: Boolean = false,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    val assignees: List<String> = emptyList(),
    val mine: Boolean = false,
    @SerialName("org_id") val orgId: Long? = null,
    val group: GroupRef = GroupRef(),
    val counts: TaskCounts = TaskCounts(),
    @SerialName("needs_you") val needsYou: Boolean = false,
    val review: Boolean = false,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    val repos: List<String> = emptyList(),
    /** Active (primary first), suggested, then ended newest first — the hub's order. */
    val sessions: List<TaskLink> = emptyList(),
    /** Links a tree page left out (`per_task`); the task screen lists them all. */
    @SerialName("sessions_more") val sessionsMore: Int = 0,
) {
    /** `PAY-7 Refund flow`, or whichever half there is. */
    val label: String
        get() = listOfNotNull(key, title.takeIf { it.isNotBlank() }).joinToString(" ").ifEmpty { taskId }
}

/** A section header of a tree page: one org's group and how many tasks match. */
@Serializable
data class TreeGroup(
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("org_name") val orgName: String? = null,
    val group: GroupRef = GroupRef(),
    val count: Int = 0,
)

/** An org this token sees. */
@Serializable
data class OrgBrief(val id: Long, val name: String = "", val color: String? = null)

/** A tracker this token sees. */
@Serializable
data class TrackerBrief(
    val id: Long,
    val name: String = "",
    val provider: String = "",
    val state: String = "",
    @SerialName("org_id") val orgId: Long? = null,
)

/** `work { action: tree }`: one page, and the headers of every section under the filters. */
@Serializable
data class TreePage(
    val tasks: List<WorkTask> = emptyList(),
    val groups: List<TreeGroup> = emptyList(),
    val orgs: List<OrgBrief> = emptyList(),
    val trackers: List<TrackerBrief> = emptyList(),
    val total: Int = 0,
    /** Opaque; valid only with the filters it was answered for. */
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("generated_at") val generatedAt: Long = 0,
)

/** A task's newest past session the caller may see, and what it left. */
@Serializable
data class LastOutcome(
    val at: Long = 0,
    val name: String = "",
    val host: String? = null,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("end_reason") val endReason: String? = null,
    val summary: String? = null,
)

/** `work { action: task }`: one task with every session, and what the hub knows around it. */
@Serializable
data class TaskDetail(
    val task: WorkTask,
    /** Other ids naming this task (a bare key a sync bound to an item). */
    val aliases: List<String> = emptyList(),
    /** The tracker's description: third-party text, capped by the hub. */
    val description: String? = null,
    @SerialName("last_outcome") val lastOutcome: LastOutcome? = null,
)

/** The task one of a session's links points at, briefly (`TaskBrief`). */
@Serializable
data class TaskBrief(
    @SerialName("task_id") val taskId: String,
    val key: String? = null,
    val title: String = "",
    val kind: String = "",
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    val unavailable: Boolean = false,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("tracker_name") val trackerName: String? = null,
) {
    val label: String
        get() = listOfNotNull(key, title.takeIf { it.isNotBlank() }).joinToString(" ").ifEmpty { taskId }
}

/**
 * One link of a session with its task. On the wire the link's fields are
 * flattened beside `task` (serde's `flatten`), which kotlinx cannot express,
 * so the client reads the two halves of one object separately.
 */
data class SessionTaskLink(val link: TaskLink, val task: TaskBrief)

/** `work { action: session_tasks }`: every link of one session — live, suggested, rejected, ended. */
data class SessionTasks(
    val sessionId: Long,
    val primaryLinkId: Long? = null,
    val links: List<SessionTaskLink> = emptyList(),
)

/** A saved view (`WorkView`): a name and the tree's filters. Shared on the hub (D35); a bound phone lists its org's. */
@Serializable
data class WorkView(
    val id: Long,
    val name: String = "",
    val filters: JsonElement? = null,
    val version: Long = 0,
) {
    /** The filters as the phone models them, or null when they cannot be read at all. */
    val parsed: WorkFilters? get() = WorkFilters.fromJson(filters)
}

/** `filters.org`: one org, or unassigned work. Absent means every org this token sees. */
sealed interface OrgChoice {
    data class Org(val id: Long) : OrgChoice
    data object Unassigned : OrgChoice
}

/** `filters.tracker`: one tracker, local items, or bare keys. */
sealed interface TrackerChoice {
    data class Tracker(val id: Long) : TrackerChoice
    data object Local : TrackerChoice
    data object Ref : TrackerChoice
}

enum class TaskStatusChoice(val wire: String, val label: String) {
    Any("any", "Any"),
    Open("open", "Open"),
    Todo("todo", "To do"),
    InProgress("in_progress", "In progress"),
    Done("done", "Done"),
}

enum class TaskHasChoice(val wire: String, val label: String) {
    Any("any", "Any"),
    Active("active", "With a session"),
    PastOnly("past_only", "Past only"),
    None("none", "No session"),
    Suggested("suggested", "Suggested"),
}

/**
 * `WorkTreeFilters`: one object for a tree page, a saved view, the desktop
 * and the phone. The defaults are "no filter", and [toJson] leaves every
 * default out, so an empty filter is an empty object — what the hub reads
 * as "all visible".
 */
data class WorkFilters(
    val org: OrgChoice? = null,
    val tracker: TrackerChoice? = null,
    val status: TaskStatusChoice = TaskStatusChoice.Any,
    val mine: Boolean = false,
    val has: TaskHasChoice = TaskHasChoice.Any,
    val review: Boolean = false,
    val query: String = "",
    /** One group only: a section being paged, or a saved view that names one. */
    val group: String? = null,
) {
    /** How many filters narrow the tree — the filter button's badge. [group] is a section's, not a person's. */
    val active: Int
        get() = listOf(
            org != null,
            tracker != null,
            status != TaskStatusChoice.Any,
            mine,
            has != TaskHasChoice.Any,
            review,
            query.isNotBlank(),
        ).count { it }

    fun toJson(): JsonObject = buildJsonObject {
        when (val o = org) {
            is OrgChoice.Org -> put("org", o.id)
            OrgChoice.Unassigned -> put("org", "none")
            null -> Unit
        }
        when (val t = tracker) {
            is TrackerChoice.Tracker -> put("tracker", t.id)
            TrackerChoice.Local -> put("tracker", "local")
            TrackerChoice.Ref -> put("tracker", "ref")
            null -> Unit
        }
        if (status != TaskStatusChoice.Any) put("status", status.wire)
        if (mine) put("mine", true)
        if (has != TaskHasChoice.Any) put("has", has.wire)
        if (review) put("review", true)
        query.trim().takeIf { it.isNotEmpty() }?.let { put("query", it.take(QUERY_MAX)) }
        group?.let { put("group", it) }
    }

    companion object {
        /** The hub refuses a longer `filters.query`. */
        const val QUERY_MAX = 200

        /**
         * A saved view's filters. Lenient the way a row is: a word this build
         * does not know reads as "no filter" for that field rather than
         * failing the view. Null only when [element] is not an object.
         */
        fun fromJson(element: JsonElement?): WorkFilters? {
            val o = element as? JsonObject ?: return null
            fun prim(name: String) = o[name] as? JsonPrimitive
            fun word(name: String) = prim(name)?.takeIf { it.isString }?.content
            val org = prim("org")?.let { p ->
                p.longOrNull?.takeIf { !p.isString }?.let { OrgChoice.Org(it) }
                    ?: OrgChoice.Unassigned.takeIf { p.isString && p.content == "none" }
            }
            val tracker = prim("tracker")?.let { p ->
                p.longOrNull?.takeIf { !p.isString }?.let { TrackerChoice.Tracker(it) }
                    ?: when (p.content.takeIf { p.isString }) {
                        "local" -> TrackerChoice.Local
                        "ref" -> TrackerChoice.Ref
                        else -> null
                    }
            }
            return WorkFilters(
                org = org,
                tracker = tracker,
                status = TaskStatusChoice.entries.firstOrNull { it.wire == word("status") } ?: TaskStatusChoice.Any,
                mine = prim("mine")?.booleanOrNull == true,
                has = TaskHasChoice.entries.firstOrNull { it.wire == word("has") } ?: TaskHasChoice.Any,
                review = prim("review")?.booleanOrNull == true,
                query = word("query").orEmpty(),
                group = word("group"),
            )
        }
    }
}
