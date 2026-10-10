package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One organisation as `work { action: orgs }` lists it (claude-fleet M5's
 * `OrgDetail`): the name and colour a label draws, and the trackers whose
 * tickets belong to it, and the rules that place sessions in it (read by
 * Add a project, to say which org a repository lands in).
 *
 * Everything past [trackers] is the org overview (claude-fleet's company
 * administration, phases A–D), read by the Company screen. The hub decides
 * who is sent what: [devices], spend and budgets only to whoever administers
 * the org, [members] to its administrators and its own people, [myRole] only
 * to a member. Absent is "not yours to see" (or an older hub), never zero —
 * so each of those stays null rather than defaulting to an empty value.
 */
@Serializable
data class OrgDetail(
    val id: Long,
    val name: String = "",
    val color: String? = null,
    val trackers: List<OrgTracker> = emptyList(),
    val rules: List<OrgRule> = emptyList(),
    val hosts: List<String> = emptyList(),
    @SerialName("session_count") val sessionCount: Int = 0,
    @SerialName("needs_you") val needsYou: Int = 0,
    /** This company owns the hub, so its admins administer every host. */
    @SerialName("owns_hub") val ownsHub: Boolean = false,
    val devices: List<OrgDevice>? = null,
    @SerialName("spent_today_micros") val spentTodayMicros: Long? = null,
    @SerialName("spent_week_micros") val spentWeekMicros: Long? = null,
    @SerialName("spent_month_micros") val spentMonthMicros: Long? = null,
    /** Whole USD; `0` is "no budget". */
    @SerialName("budget_daily_usd") val budgetDailyUsd: Long? = null,
    @SerialName("budget_monthly_usd") val budgetMonthlyUsd: Long? = null,
    /** `daily` / `monthly`: the budgets it has reached. */
    @SerialName("over_budget") val overBudget: List<String> = emptyList(),
    val members: List<OrgMember>? = null,
    /** `admin` / `member` / `viewer`; null when the caller is not in it. */
    @SerialName("my_role") val myRole: String? = null,
    /** This org consented to Jev (decision-model) calls; null from a hub that does not say. */
    @SerialName("jev_allowed") val jevAllowed: Boolean? = null,
)

/**
 * One rule that places sessions in an org (claude-fleet M5, `org_rules`):
 * every field it sets must match — the project's owner and repository
 * (case-insensitively), the working path on a directory boundary, the host.
 */
@Serializable
data class OrgRule(
    val id: Long = 0,
    @SerialName("org_id") val orgId: Long,
    val owner: String? = null,
    val repo: String? = null,
    @SerialName("path_prefix") val pathPrefix: String? = null,
    @SerialName("host_alias") val hostAlias: String? = null,
)

@Serializable
data class OrgTracker(val id: Long, val name: String = "")

/** A paired device bound to an org. The hub never sends its token. */
@Serializable
data class OrgDevice(
    val name: String = "",
    /** `full` or `readonly`. */
    val mode: String = "",
    val trusted: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
)

/** A person in an org, with their role (`admin` / `member` / `viewer`). */
@Serializable
data class OrgMember(
    @SerialName("person_id") val personId: Long,
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
    val role: String = "",
) {
    /** What a list draws: the display name when there is one. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
}

/** The hub sent this caller the org's spend (its administrators only). */
val OrgDetail.hasSpend: Boolean get() = spentTodayMicros != null || spentWeekMicros != null || spentMonthMicros != null

/** An org as a label draws it. */
data class OrgInfo(val id: Long, val name: String, val color: String? = null)

/**
 * The orgs this token can see, read once per connection: what an org id on a
 * row, a work link, a Today entry or a ticket's tracker is called.
 *
 * The fence is the hub's, never this directory's. A phone paired plainly is
 * unscoped (`OrgScope::All`) and sees every org; one paired with
 * `fleet-hub pair --org <id>` (claude-fleet M14.1b, `OrgScope::Org`) is
 * listed its own org alone, sees that org's rows (and unassigned ones while
 * the org's `bound_sees_unassigned` is on), and is answered "not found" for
 * anything else. Either way everything here is a way of *reading* what came
 * back — a label, a filter — and nothing offers an org the hub did not list.
 */
data class OrgDirectory(
    val orgs: Map<Long, OrgInfo> = emptyMap(),
    /** Tracker id → org id, from each org's tracker list. */
    val trackerOrg: Map<Long, Long> = emptyMap(),
    /** Every org's rules, for [projectOrg]-style placement on the phone. */
    val rules: List<OrgRule> = emptyList(),
    /** Host alias → the org it is assigned to. */
    val hostOrg: Map<String, Long> = emptyMap(),
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
            rules = details.flatMap { it.rules },
            hostOrg = details.flatMap { d -> d.hosts.map { it to d.id } }.toMap(),
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
