package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Activity
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.unnamedProject

/**
 * Which sessions the list is showing, by how they are being used.
 *
 * One fleet this screen is used against runs fifty-odd sessions across five
 * machines and a dozen projects. The screen's only filter used to be a "needs
 * attention" toggle — `blocked` or stuck — which on an ordinary afternoon
 * matches nothing at all, so the choice was between an empty screen and every
 * row there is. These are the middle ground: the same question the toggle
 * asked, plus the two that are actually asked most often.
 *
 * The order is the order they are drawn in, narrowest first.
 */
enum class Lens {
    /** Blocked, or stuck: the rows that will not move until a person moves them. */
    NeedsYou,

    /** Working now, waiting on a person, or stamped within [ACTIVE_SECONDS]. */
    Active,

    /** Anything the hub has stamped within [DORMANT_SECONDS]. */
    Today,

    /** Every session the hub reports, dormant ones included. */
    All,
}

/**
 * How recent a stamp still counts as "right now".
 *
 * Fifteen minutes rather than five: the hub stamps `last_activity_at` when a
 * pane changes, and an agent that is thinking, compiling or waiting on a
 * network call can be genuinely busy and quiet for several minutes at a time.
 * A five-minute window made the Active lens flicker rows in and out while
 * nothing about the work had changed.
 */
const val ACTIVE_SECONDS: Long = 15L * 60

/**
 * How long a session has to be quiet before it moves to the tail.
 *
 * A day, so "today" and "not dormant" are the same boundary rather than two
 * numbers that can drift apart — the `Today` lens excluding a row that the
 * `All` lens then drew in the live part above the tail would be a picture of
 * two different rules.
 */
const val DORMANT_SECONDS: Long = 24L * 60 * 60

/**
 * The sessions of one project on one host.
 */
data class ProjectGroup(
    /** Null for sessions that belong to no project at all — a shell session. */
    val projectId: Long?,
    val label: String,
    val sessions: List<SessionRow>,
)

/** One machine's sessions, cut into projects. */
data class HostGroup(
    val alias: String,
    /**
     * What `list_hosts` said. **Null means unknown**, not unreachable: a session
     * row names its host, and the host list may not have arrived yet or may not
     * contain it at all.
     */
    val reachable: Boolean?,
    val projects: List<ProjectGroup>,
    /**
     * How many sessions are behind this heading.
     *
     * Stored rather than summed from [projects], because a [collapsed] group
     * carries no projects at all and the count is the whole of what its heading
     * has to say. Summing would have made every collapsed host read "0", which
     * is the one number that would stop anyone expanding it again.
     */
    val sessionCount: Int,
    /** Whether the person has folded this host away. Its rows are not built. */
    val collapsed: Boolean = false,
)

/**
 * Everything the list draws, once the lens, the search, the noise switch and
 * the collapses have been applied.
 */
data class Triage(
    /** Host groups, in the live part of the screen. Empty while [results] is set. */
    val groups: List<HostGroup> = emptyList(),
    /** What has gone quiet for over a day, flat and newest first. */
    val dormant: List<SessionRow> = emptyList(),
    /**
     * A flat answer to a search, or **null when nothing was asked**.
     *
     * Null and empty are different and the screen draws them differently: null
     * is "not searching", empty is "searched, found nothing".
     */
    val results: List<SessionRow>? = null,
    /** How many rows the noise switch is holding back right now. */
    val hiddenNoise: Int = 0,
)

/**
 * Whether a row is one of the two kinds that fill the list without being
 * opened: a background agent (`bg:…`) or a shell pane, which has no
 * conversation to open at all.
 *
 * **Except when it needs a person.** A background agent sitting on a permission
 * prompt is the most urgent row on the screen and the one nobody is watching a
 * pane for; hiding it on the strength of the shape of its name would be the
 * worst thing this switch could do.
 */
internal fun SessionRow.isNoise(): Boolean =
    !needsAttention && (isBackground || kind == "shell")

/**
 * Whether a row has gone quiet long enough to belong in the tail.
 *
 * A row that is `working` or wants a person is never dormant however stale its
 * stamp: both are statements about what the pane says *now*, and the stamp is
 * only a record of when it last changed. A row the hub has never stamped is
 * dormant — nothing has ever happened on it that the hub saw.
 */
internal fun SessionRow.isDormant(nowSeconds: Long): Boolean {
    if (claudeStatus == "working" || needsAttention) return false
    val stamp = lastActivityAt ?: return true
    return nowSeconds - stamp > DORMANT_SECONDS
}

/** Whether [Lens] keeps this row. */
internal fun Lens.keeps(row: SessionRow, nowSeconds: Long): Boolean = when (this) {
    Lens.All -> true
    Lens.NeedsYou -> row.needsAttention
    Lens.Active -> {
        val stamp = row.lastActivityAt
        row.claudeStatus == "working" ||
            row.needsAttention ||
            (stamp != null && nowSeconds - stamp <= ACTIVE_SECONDS)
    }
    Lens.Today -> {
        val stamp = row.lastActivityAt
        row.claudeStatus == "working" ||
            row.needsAttention ||
            (stamp != null && nowSeconds - stamp <= DORMANT_SECONDS)
    }
}

/**
 * Everything about a row that a search may match, lowercased once.
 *
 * The activity is included **as the screen shows it** — through
 * [Activity.sanitize] — and not as the hub sent it. Raw `current_activity`
 * carries ANSI escapes and the REPL's own footer, so a query over the raw text
 * would find rows on the strength of terminal punctuation nobody can see, and
 * `bypass` would match almost every row in the fleet.
 */
private fun SessionRow.searchable(projectLabel: String?): List<String> = buildList {
    add(displayName)
    add(tmuxName)
    add(hostAlias)
    projectLabel?.let { add(it) }
    lastPrompt?.let { add(it) }
    Activity.sanitize(currentActivity)?.let { add(it) }
    branch?.let { add(it) }
    addAll(tags)
}

/** Whether [needle], already lowercased and trimmed, appears anywhere on the row. */
internal fun SessionRow.matches(needle: String, projectLabel: String?): Boolean =
    searchable(projectLabel).any { it.lowercase().contains(needle) }

/** The label a project group carries, given what `list_projects` last said. */
internal fun projectLabel(id: Long?, byId: Map<Long, ProjectRow>): String = when (id) {
    null -> NO_PROJECT
    else -> byId[id]?.label ?: unnamedProject(id)
}

/** The heading for sessions that belong to no project — a shell session, say. */
internal const val NO_PROJECT = "No project"

/** Most recently active first; never-stamped rows last; ties broken by id. */
internal val BY_RECENCY: Comparator<SessionRow> =
    compareByDescending<SessionRow> { it.lastActivityAt ?: Long.MIN_VALUE }.thenBy { it.id }

/**
 * The whole of what the sessions list shows, as one pure function.
 *
 * Pure, and deliberately the only place any of these rules live, because every
 * one of them is an off-by-one or a boundary that no screenshot would settle:
 * what counts as active, what counts as dormant, whether a search looks past
 * the lens, whether a blocked background agent may be hidden.
 *
 * The order of operations is the argument:
 *
 *  1. **The host filter first**, because it is where the person is rather than
 *     something they asked for — a search run from a host-filtered screen
 *     answers about that host.
 *  2. **A search short-circuits everything else.** A query looks at the whole
 *     fleet and comes back as one flat list: no lens, no noise switch, no tail.
 *     A search that only looked inside the current lens would answer "no such
 *     session" about a session that is plainly there, and that is the one
 *     answer a search must never give wrongly.
 *  3. Otherwise the lens, then the noise switch, then the split into the live
 *     part and the tail, then the grouping.
 *
 * The group order is fixed rather than inherited from the hub, whose order is
 * not stable across calls, and a list that reshuffles under a thumb is worse
 * than one that is merely sorted oddly: hosts alphabetically; projects
 * alphabetically by label with "no project at all" last, since it is a
 * leftovers bin rather than a name; sessions most recently active first.
 */
internal fun triageSessions(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    lens: Lens,
    query: String,
    hideNoise: Boolean,
    hostFilter: String?,
    collapsedHosts: Set<String>,
    nowSeconds: Long,
): Triage {
    val byId = projects.associateBy { it.id }
    val onHost = if (hostFilter != null) sessions.filter { it.hostAlias == hostFilter } else sessions

    val needle = query.trim().lowercase()
    if (needle.isNotEmpty()) {
        return Triage(
            results = onHost
                .filter { it.matches(needle, projectLabel(it.projectId, byId)) }
                .sortedWith(BY_RECENCY),
        )
    }

    val inLens = onHost.filter { lens.keeps(it, nowSeconds) }
    // Counted on what the lens kept, not on the whole fleet: the switch's
    // label promises what turning it off would bring back *here*, and a count
    // that included rows the lens had already excluded would promise rows that
    // never appeared.
    val hiddenNoise = if (hideNoise) inLens.count { it.isNoise() } else 0
    val kept = if (hideNoise) inLens.filterNot { it.isNoise() } else inLens

    // Split unconditionally, and deliberately not behind a `lens == All` guard.
    //
    // The guard was written first and a mutation sweep proved it could not
    // fail: under any narrower lens the split is already empty, because the
    // lenses and [isDormant] are the same boundary read from the two sides.
    // `Today` keeps a row stamped within [DORMANT_SECONDS], or working, or
    // waiting on a person; [isDormant] excepts those same three. `Active` is a
    // subset of `Today` and `NeedsYou` is all `needsAttention`, which is never
    // dormant. So nothing a narrow lens keeps can be dormant, and a branch
    // whose two arms cannot differ is a claim nothing can check.
    val dormant = kept.filter { it.isDormant(nowSeconds) }
    val live = kept.filterNot { it.isDormant(nowSeconds) }

    return Triage(
        groups = group(live, hosts, byId, collapsedHosts),
        dormant = dormant.sortedWith(BY_RECENCY),
        hiddenNoise = hiddenNoise,
    )
}

private fun group(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    byId: Map<Long, ProjectRow>,
    collapsedHosts: Set<String>,
): List<HostGroup> {
    if (sessions.isEmpty()) return emptyList()
    val reachability = hosts.associate { it.alias to it.reachable }

    // `entries.sortedBy` rather than `toSortedMap()`: the latter is a JVM-only
    // extension and this file compiles for iOS too.
    return sessions.groupBy { it.hostAlias }
        .entries
        .sortedBy { it.key }
        .map { (alias, rows) ->
            val collapsed = alias in collapsedHosts
            HostGroup(
                alias = alias,
                reachable = reachability[alias],
                sessionCount = rows.size,
                collapsed = collapsed,
                projects = if (collapsed) {
                    emptyList()
                } else {
                    rows.groupBy { it.projectId }
                        .map { (id, inProject) ->
                            ProjectGroup(
                                projectId = id,
                                label = projectLabel(id, byId),
                                sessions = inProject.sortedWith(BY_RECENCY),
                            )
                        }
                        // `null` last whatever it is called, then by label.
                        .sortedWith(compareBy({ it.projectId == null }, { it.label }))
                },
            )
        }
}
