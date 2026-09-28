package dev.claudefleet.mobile.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The Work view (claude-fleet M14): `work { tree | task | session_tasks |
 * review | rules | rule_preview | views | org_impact }` and what the new
 * `work_link` writes answer. Mirrors the contract in claude-fleet's
 * `docs/superpowers/specs/2026-09-27-work-view-design.md`; the JSON names
 * there are the authority.
 *
 * Every field is defaulted, because the hub strips nulls from what it sends
 * (and skips empty optional fields), and every wire enum reads a value a
 * later hub adds as `Unknown` rather than failing the row it rides on — the
 * same rule `StatusCategory` keeps. A field the contract types loosely
 * (`org` may be an id or `"none"`) is modelled so either spelling decodes.
 */

/**
 * A filter value that is an id **or** a word — `org: 3` or `org: "none"`,
 * `tracker: 1` or `"local"` / `"ref"`. Kept as the text it was, and sent
 * back as a JSON number when it is one, so a hub that compares numbers is
 * never handed `"3"`.
 */
@Serializable(with = IdOrWordSerializer::class)
data class IdOrWord(val raw: String) {
    /** The id, when this is one. */
    val id: Long? get() = raw.toLongOrNull()

    override fun toString(): String = raw

    companion object {
        fun of(id: Long) = IdOrWord(id.toString())
        val NONE = IdOrWord("none")
        val LOCAL = IdOrWord("local")
        val REF = IdOrWord("ref")
    }
}

internal object IdOrWordSerializer : KSerializer<IdOrWord> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IdOrWord", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IdOrWord) {
        val id = value.id
        if (id != null) encoder.encodeLong(id) else encoder.encodeString(value.raw)
    }

    override fun deserialize(decoder: Decoder): IdOrWord {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            return IdOrWord((element as? JsonPrimitive)?.content ?: element.toString())
        }
        return IdOrWord(decoder.decodeString())
    }
}

/**
 * The one filter object of the Work view: tree pages, saved views, desktop
 * and phone. Absent means "any"; [normalized] makes "any" absent, so two
 * spellings of the same filter compare equal and the hub gets the short one.
 */
@Serializable
data class WorkTreeFilters(
    /** An org id, or `"none"` (unassigned); null = every visible org. */
    val org: IdOrWord? = null,
    /** A tracker id, `"local"` (local items) or `"ref"` (bare keys). */
    val tracker: IdOrWord? = null,
    /** `any` | `open` | `todo` | `in_progress` | `done`. */
    val status: String? = null,
    /** Assigned to me in its tracker. */
    val mine: Boolean? = null,
    /** `any` | `active` | `past_only` | `none` | `suggested`. */
    val has: String? = null,
    /** Only tasks with something to review. */
    val review: Boolean? = null,
    /** Case-insensitive substring of key or title. */
    val query: String? = null,
    /** One group only — a section being expanded. Never saved in a view. */
    val group: String? = null,
    /**
     * Include archived tasks — done, or every session link archived, with no
     * active session. Absent (or false) hides them and the page says how many
     * ([WorkTreePage.archivedHidden]); an explicit `status: done` shows done
     * tasks anyway. An older hub ignores it and hides nothing.
     */
    val archived: Boolean? = null,
) {
    /**
     * "Any" spelled as absent, a blank query dropped, a `false` toggle
     * dropped — and a status or sessions value this build does not offer
     * dropped too (the desktop's `normalizeFilters`): a stale remembered
     * value, or one a newer desktop saved in a view, would otherwise narrow
     * the tree with no chip selected in the sheet to show it or clear it.
     */
    fun normalized(): WorkTreeFilters = WorkTreeFilters(
        org = org?.takeIf { it.raw.isNotBlank() },
        tracker = tracker?.takeIf { it.raw.isNotBlank() },
        status = status?.takeIf { it in STATUSES },
        mine = mine?.takeIf { it },
        has = has?.takeIf { it in HAS },
        review = review?.takeIf { it },
        query = query?.trim()?.takeIf { it.isNotEmpty() },
        group = group?.takeIf { it.isNotBlank() },
        archived = archived?.takeIf { it },
    )

    /**
     * How many filters a person set. The group (navigation), the search and
     * *Show archived* (it widens the tree) are not counted.
     */
    val count: Int get() = with(normalized()) {
        listOfNotNull(org, tracker, status, mine, has, review).size
    }

    /**
     * What the *Filters (n)* button counts: the filters held in the sheet —
     * organisation, tracker, status, sessions. *Assigned to me* and *To
     * review* are toggles on screen and show their own state.
     */
    val sheetCount: Int get() = with(normalized()) {
        listOfNotNull(org, tracker, status, has).size
    }

    /** Nothing narrows the tree (search included). Showing archived tasks is not a narrowing. */
    val isEmpty: Boolean get() = normalized().copy(group = null, archived = null) == WorkTreeFilters()

    companion object {
        const val ANY = "any"
        val STATUSES = listOf("open", "todo", "in_progress", "done")
        val HAS = listOf("active", "past_only", "none", "suggested")

        /** The desktop's `STATUS_FILTER_LABELS` (`work_view.ts`). */
        fun statusLabel(status: String): String = when (status) {
            ANY -> "Any"
            "open" -> "Open"
            "todo" -> "To do"
            "in_progress" -> "In progress"
            "done" -> "Done"
            else -> status
        }

        /** The desktop's `HAS_FILTER_LABELS` (`work_view.ts`). */
        fun hasLabel(has: String): String = when (has) {
            ANY -> "Any"
            "active" -> "Active session"
            "past_only" -> "Past only"
            "none" -> "No session"
            "suggested" -> "Suggested"
            else -> has
        }
    }
}

/**
 * A wire enum that tolerates the future: [wire] is the JSON spelling, and a
 * value this build does not know decodes as the enum's `Unknown`.
 */
interface WireEnum {
    val wire: String
}

internal open class WireEnumSerializer<E>(
    name: String,
    private val values: List<E>,
    private val unknown: E,
) : KSerializer<E> where E : Enum<E>, E : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(value.wire)
    override fun deserialize(decoder: Decoder): E {
        val text = decoder.decodeString()
        return values.firstOrNull { it != unknown && it.wire == text } ?: unknown
    }
}

/** Where a task's group comes from — and so whether a person's placement overrides it. */
@Serializable(with = GroupSourceSerializer::class)
enum class GroupSource(override val wire: String) : WireEnum {
    Manual("manual"),
    Rule("rule"),
    Tracker("tracker"),
    Repo("repo"),
    Key("key"),
    None("none"),
    Unknown(""),
}

internal object GroupSourceSerializer : WireEnumSerializer<GroupSource>("GroupSource", GroupSource.entries, GroupSource.Unknown)

/** A link as the Work view states it. `ended` is never drawn as active. */
@Serializable(with = LinkStateSerializer::class)
enum class LinkState(override val wire: String) : WireEnum {
    Active("active"),
    Ended("ended"),
    Suggested("suggested"),
    Rejected("rejected"),
    Unknown(""),
}

internal object LinkStateSerializer : WireEnumSerializer<LinkState>("LinkState", LinkState.entries, LinkState.Unknown)

/** A tracker item, local work (a title, no ticket), or a bare key. */
@Serializable(with = TaskKindSerializer::class)
enum class TaskKind(override val wire: String) : WireEnum {
    Tracker("tracker"),
    Local("local"),
    Ref("ref"),
    Unknown(""),
}

internal object TaskKindSerializer : WireEnumSerializer<TaskKind>("TaskKind", TaskKind.entries, TaskKind.Unknown)

/** Where a task's organisation comes from. */
@Serializable(with = OrgSourceSerializer::class)
enum class OrgSource(override val wire: String) : WireEnum {
    Tracker("tracker"),
    Item("item"),
    Sessions("sessions"),
    None("none"),
    Unknown(""),
}

internal object OrgSourceSerializer : WireEnumSerializer<OrgSource>("OrgSource", OrgSource.entries, OrgSource.Unknown)

/** What a review item asks a person to decide. */
@Serializable(with = ReviewKindSerializer::class)
enum class ReviewKind(override val wire: String) : WireEnum {
    Suggestion("suggestion"),
    CrossOrg("cross_org"),
    Unavailable("unavailable"),
    NoPrimary("no_primary"),
    Unknown(""),
}

internal object ReviewKindSerializer : WireEnumSerializer<ReviewKind>("ReviewKind", ReviewKind.entries, ReviewKind.Unknown)

/** A task's project / group: navigation only, never a boundary. */
@Serializable
data class GroupRef(
    /**
     * `label:<label>` (a person's placement or a rule — [source] says which),
     * `tracker:<id>:<container>`, `repo:<owner/repo>`, `key:<PREFIX>`, `none`.
     */
    val id: String = NO_GROUP,
    val label: String = "",
    val source: GroupSource = GroupSource.None,
    @SerialName("rule_id") val ruleId: Long? = null,
    /** The tracker's own container value, when the tracker says where the task lives. */
    @SerialName("tracker_value") val trackerValue: String? = null,
    val editable: Boolean = true,
) {
    /** What a heading says. */
    val title: String get() = label.ifBlank { if (id == NO_GROUP) "No group" else id }

    companion object {
        const val NO_GROUP = "none"
    }
}

/** How many sessions of each kind a task has. */
@Serializable
data class TaskCounts(
    val active: Int = 0,
    val ended: Int = 0,
    val suggested: Int = 0,
)

/** The task a link of a session points at — `session_tasks` only. */
@Serializable
data class LinkTask(
    @SerialName("task_id") val taskId: String = "",
    val key: String? = null,
    val title: String = "",
    val kind: TaskKind = TaskKind.Unknown,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    val unavailable: Boolean = false,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("tracker_name") val trackerName: String? = null,
) {
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title.ifBlank { taskId }
}

/** One session under a task, or one task of a session (with [task] set). */
@Serializable
data class WorkTaskLink(
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("link_version") val linkVersion: Long = 0,
    val state: LinkState = LinkState.Unknown,
    val primary: Boolean = false,
    /** The live session; absent once it has ended. */
    @SerialName("session_id") val sessionId: Long? = null,
    val name: String = "",
    val host: String = "",
    val source: String = "",
    val strength: String? = null,
    val rule: String? = null,
    /** One line, from the evidence. */
    val why: String? = null,
    /** `task` / `session_tasks` only. Kept as the hub's JSON: the phone draws [why]. */
    val evidence: List<JsonElement> = emptyList(),
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("decided_at") val decidedAt: Long? = null,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("end_reason") val endReason: String? = null,
    @SerialName("claude_status") val claudeStatus: String? = null,
    @SerialName("needs_you") val needsYou: Boolean = false,
    val archived: Boolean = false,
    val resumable: Boolean = false,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("cross_org") val crossOrg: Boolean = false,
    /** Other active tasks of this session that the caller sees. */
    @SerialName("other_tasks") val otherTasks: Int = 0,
    /** The task, on a `session_tasks` answer. */
    val task: LinkTask? = null,
)

/** One task of the Work view: a tracker item, local work, or a bare key. */
@Serializable
data class WorkTask(
    /** `item:<id>` or `ref:<KEY>`. The identity; titles and positions are attributes. */
    @SerialName("task_id") val taskId: String = "",
    @SerialName("item_id") val itemId: Long? = null,
    val key: String? = null,
    /** Third-party text: plain text only. */
    val title: String = "",
    val url: String? = null,
    val kind: TaskKind = TaskKind.Unknown,
    @SerialName("tracker_id") val trackerId: Long? = null,
    @SerialName("tracker_name") val trackerName: String? = null,
    val provider: String? = null,
    /** The tracker's sync state: an outage is not "no sessions". */
    @SerialName("tracker_state") val trackerState: String? = null,
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    val resolution: String? = null,
    val unavailable: Boolean = false,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
    val assignees: List<String> = emptyList(),
    val mine: Boolean = false,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("org_source") val orgSource: OrgSource = OrgSource.None,
    /** The org is a boundary (tracker / item), not inferred. */
    @SerialName("org_fenced") val orgFenced: Boolean = false,
    /** An unfenced task whose sessions span orgs. */
    @SerialName("org_mixed") val orgMixed: Boolean = false,
    val group: GroupRef = GroupRef(),
    val counts: TaskCounts = TaskCounts(),
    @SerialName("needs_you") val needsYou: Boolean = false,
    val review: Boolean = false,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    val repos: List<String> = emptyList(),
    /** The placement's version, `0` when there is none — what `place` sends as `expected_version`. */
    @SerialName("placement_version") val placementVersion: Long = 0,
    /** Active (primary first), suggested, ended newest first. */
    val sessions: List<WorkTaskLink> = emptyList(),
    @SerialName("sessions_more") val sessionsMore: Int = 0,
    /**
     * No active session, and done or every session link archived: the tree
     * hides it unless the filters ask for archived tasks (or for done ones).
     * An older hub does not send it.
     */
    val archived: Boolean = false,
) {
    /** The key, else the title, else the id. */
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title.ifBlank { taskId }

    /** The tracker is failing — said as such, never as "no sessions". */
    val trackerDown: Boolean get() = trackerId != null && !trackerState.isNullOrBlank() && trackerState != "ok"
}

/** One section header of a tree: every group of the filtered result, with its count. */
@Serializable
data class TreeGroup(
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("org_name") val orgName: String? = null,
    val group: GroupRef = GroupRef(),
    val count: Int = 0,
)

@Serializable
data class TreeOrg(val id: Long = 0, val name: String = "", val color: String? = null)

@Serializable
data class TreeTracker(
    val id: Long = 0,
    val name: String = "",
    val provider: String = "",
    val state: String = "",
    @SerialName("org_id") val orgId: Long? = null,
)

/** `work { tree }`: one page of tasks, and every section header of the whole filtered result. */
@Serializable
data class WorkTreePage(
    val tasks: List<WorkTask> = emptyList(),
    val groups: List<TreeGroup> = emptyList(),
    val orgs: List<TreeOrg> = emptyList(),
    val trackers: List<TreeTracker> = emptyList(),
    val total: Int = 0,
    /**
     * Tasks that passed every other filter but were hidden as archived, over
     * the whole result (not the page). 0 from an older hub, which hides none.
     */
    @SerialName("archived_hidden") val archivedHidden: Int = 0,
    @SerialName("next_cursor") val nextCursor: String? = null,
    /** Hub seconds: when the page was read. What "as of" says when the phone is offline. */
    @SerialName("generated_at") val generatedAt: Long? = null,
)

/** What the last session on a task ended with. */
@Serializable
data class LastOutcome(
    val at: Long? = null,
    val name: String? = null,
    val host: String? = null,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    val summary: String? = null,
)

/** A person's placement of a task in a group. */
@Serializable
data class Placement(
    /** The group — its label, or a [GroupRef] object; both spellings are read. */
    val group: JsonElement? = null,
    val note: String? = null,
    val version: Long = 0,
    @SerialName("updated_at") val updatedAt: Long? = null,
    @SerialName("updated_by") val updatedBy: JsonElement? = null,
) {
    val groupLabel: String?
        get() = when (val g = group) {
            is JsonPrimitive -> g.content.takeIf { g.isString && it.isNotBlank() }
            is JsonObject -> (g["label"] as? JsonPrimitive)?.content ?: (g["id"] as? JsonPrimitive)?.content
            else -> null
        }
}

/** `work { task }`: the task with all its sessions, and where its org and group come from. */
@Serializable
data class TaskDetail(
    val task: WorkTask = WorkTask(),
    /** Old ids of this task (a bare key a sync bound to an item), so a selection survives. */
    val aliases: List<String> = emptyList(),
    /** Tracker text, fenced; plain text only. */
    val description: String? = null,
    @SerialName("last_outcome") val lastOutcome: LastOutcome? = null,
    val placement: Placement? = null,
    /** The ids of the placement rules that match this task. */
    val rules: List<Long> = emptyList(),
)

/** `work { session_tasks }`: every link of one session — live, suggested, rejected and ended. */
@Serializable
data class SessionTasks(
    @SerialName("session_id") val sessionId: Long = 0,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("primary_link_id") val primaryLinkId: Long? = null,
    val links: List<WorkTaskLink> = emptyList(),
)

/** The task a review item is about. */
@Serializable
data class ReviewTask(
    @SerialName("task_id") val taskId: String = "",
    val key: String? = null,
    val title: String = "",
    @SerialName("org_id") val orgId: Long? = null,
) {
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title.ifBlank { taskId }
}

/** Another task the hub thinks the session might be on. */
@Serializable
data class ReviewAlternative(
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("task_id") val taskId: String = "",
    val key: String? = null,
    val title: String = "",
) {
    val label: String get() = key?.takeIf { it.isNotBlank() } ?: title.ifBlank { taskId }
}

/** One thing for a person to decide: a suggestion, or a conflict. */
@Serializable
data class ReviewItem(
    @SerialName("review_id") val reviewId: String = "",
    val kind: ReviewKind = ReviewKind.Unknown,
    @SerialName("session_id") val sessionId: Long = 0,
    @SerialName("session_name") val sessionName: String = "",
    val host: String = "",
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("link_version") val linkVersion: Long = 0,
    val task: ReviewTask = ReviewTask(),
    /** Visible reasons, one line each. */
    val why: List<String> = emptyList(),
    val strength: String? = null,
    val rule: String? = null,
    val preselected: Boolean = false,
    val alternatives: List<ReviewAlternative> = emptyList(),
    @SerialName("created_at") val createdAt: Long? = null,
)

@Serializable
data class ReviewPage(
    val items: List<ReviewItem> = emptyList(),
    val total: Int = 0,
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** What a placement rule matches. */
@Serializable
data class RuleConditions(
    @SerialName("tracker_id") val trackerId: Long? = null,
    val container: String? = null,
    @SerialName("key_prefix") val keyPrefix: String? = null,
    @SerialName("title_contains") val titleContains: String? = null,
    val repo: String? = null,
)

/** A placement rule: tasks that match go into [group]. */
@Serializable
data class WorkRule(
    val id: Long = 0,
    val name: String = "",
    val enabled: Boolean = true,
    val version: Long = 0,
    val conditions: RuleConditions = RuleConditions(),
    val group: String = "",
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

/** What `rule_save` / `rule_preview` send: a rule, new (no [id]) or changed. */
@Serializable
data class WorkRuleDraft(
    val id: Long? = null,
    val name: String,
    val enabled: Boolean = true,
    val conditions: RuleConditions = RuleConditions(),
    val group: String,
    @SerialName("expected_version") val expectedVersion: Long? = null,
)

@Serializable
data class RuleAffected(
    @SerialName("task_id") val taskId: String = "",
    val key: String? = null,
    val title: String = "",
    val from: GroupRef = GroupRef(),
    val to: GroupRef = GroupRef(),
)

@Serializable
data class RulePreview(
    val affected: List<RuleAffected> = emptyList(),
    val total: Int = 0,
    @SerialName("kept_manual") val keptManual: Int = 0,
)

/** A saved view: a name for a set of filters, shared on the hub. */
@Serializable
data class WorkView(
    val id: Long = 0,
    val name: String = "",
    val filters: WorkTreeFilters = WorkTreeFilters(),
    val version: Long = 0,
    @SerialName("updated_at") val updatedAt: Long? = null,
)

/** What `view_save` sends. `expected_version` 0 means "I expect none": a create never overwrites. */
@Serializable
data class WorkViewDraft(
    val id: Long? = null,
    val name: String,
    val filters: WorkTreeFilters,
    @SerialName("expected_version") val expectedVersion: Long? = null,
)

@Serializable
data class ImpactLink(
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("session_id") val sessionId: Long? = null,
    val name: String = "",
    val host: String = "",
    val state: LinkState = LinkState.Unknown,
    @SerialName("session_org") val sessionOrg: Long? = null,
    @SerialName("becomes_cross_org") val becomesCrossOrg: Boolean = false,
)

/** What moving a local task to another org would change — a security change, previewed. */
@Serializable
data class OrgImpact(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("from_org") val fromOrg: Long? = null,
    @SerialName("to_org") val toOrg: Long? = null,
    val allowed: Boolean = false,
    val reason: String? = null,
    val links: List<ImpactLink> = emptyList(),
    @SerialName("hosts_losing") val hostsLosing: List<String> = emptyList(),
    @SerialName("hosts_gaining") val hostsGaining: List<String> = emptyList(),
    @SerialName("bound_clients_losing") val boundClientsLosing: Int = 0,
    @SerialName("bound_clients_gaining") val boundClientsGaining: Int = 0,
    @SerialName("journal_entries") val journalEntries: Int = 0,
    val summaries: Int = 0,
    @SerialName("impact_token") val impactToken: String? = null,
)

/** One decision of a `decide_batch`. */
@Serializable
data class WorkDecision(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("link_id") val linkId: Long,
    /** `confirm` | `reject` | `reconsider` | `ack`. */
    val decision: String,
    @SerialName("expected_version") val expectedVersion: Long? = null,
    val primary: Boolean? = null,
)

/** How one decision of a batch went — each is checked on its own. */
@Serializable
data class DecisionResult(
    @SerialName("link_id") val linkId: Long = 0,
    val ok: Boolean = false,
    val code: String? = null,
    val message: String? = null,
    val version: Long? = null,
)

@Serializable
data class BatchResult(val results: List<DecisionResult> = emptyList())
