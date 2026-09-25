package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgInfo
import dev.claudefleet.mobile.model.orgOf
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.withTicketsFrom
import dev.claudefleet.mobile.model.unnamedProject
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The sessions of one project on one host — or, with *by work* on, of one
 * piece of work on one host, when [work] is set.
 */
data class ProjectGroup(
    /** Null for sessions that belong to no project at all — a shell session. */
    val projectId: Long?,
    val label: String,
    val sessions: List<SessionRow>,
    /**
     * Set on a work group: the most recently active session's link, which is
     * what the heading draws (key, title, status). Null on a project group.
     */
    val work: WorkSummary? = null,
    /**
     * The org a work group's heading names, when the list shows more than one
     * org and is not already narrowed to one. Null on a project group.
     */
    val orgLabel: String? = null,
) {
    /** What keys the group on screen: stable across recompositions, distinct per kind. */
    val id: String get() = work?.groupKey?.let { "work-$it" } ?: "project-${projectId ?: "none"}"

    val attentionCount: Int get() = sessions.count { it.needsAttention }
}

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
) {
    val sessionCount: Int get() = projects.sumOf { it.sessions.size }
}

/** Everything the fleet list draws. */
data class SessionsUiState(
    val groups: List<HostGroup> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    val needsAttentionOnly: Boolean = false,
    /** The host [groups] is narrowed to, or null for the whole fleet. */
    val hostFilter: String? = null,
    /** How many rows in the **whole** fleet want a person, filtered or not. */
    val attentionCount: Int = 0,
    val refreshing: Boolean = false,
    /** The last refresh's failure, in plain language with the hub's own words behind it. */
    val error: Friendly? = null,
    /** Unix seconds, refreshed every 30s by a ticker — what every row's age is computed against. */
    val nowSeconds: Long = 0,
    /** The hub has the work graph: the *By work* toggle is offered. */
    val workAvailable: Boolean = false,
    /** Sessions are grouped by their work first (only ever true when [workAvailable]). */
    val byWork: Boolean = false,
    /** The hub has a tracker and answered *My work*: the chip is offered. */
    val myWorkAvailable: Boolean = false,
    /** Narrowed to sessions on *My work* tickets (only ever true when [myWorkAvailable]). */
    val myWorkOnly: Boolean = false,
    /**
     * The orgs the fleet's sessions belong to, by name — offered as a filter
     * only when there are two or more; empty hides it.
     */
    val orgChoices: List<OrgInfo> = emptyList(),
    /** The org the list is narrowed to, or null for all of them. Only ever set while [orgChoices] offers it. */
    val orgFilter: Long? = null,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * The fleet list: sessions grouped by host and then by project, with a filter
 * for the ones that want a person.
 *
 * Plain Kotlin, not an `androidx.lifecycle.ViewModel` — the same class has to
 * run on iOS, and everything a lifecycle-aware base class would give is one
 * injected [CoroutineScope]. Whoever owns the screen owns the scope.
 *
 * It reads [FleetState] and never a `HubClient`: the rows arrive through the
 * event stream and are already in hand, so grouping and filtering cost nothing
 * and, in particular, [toggleNeedsAttentionOnly] does not talk to the hub.
 */
class SessionsViewModel(
    private val fleet: FleetState,
    private val scope: CoroutineScope,
    /**
     * The clock every age on this screen is measured against: the device's,
     * corrected by what the hub said its own time was on the last `ready`.
     * Uncorrected, a device a few minutes behind labels the whole fleet "just
     * now" and one running fast labels a working session as hours idle — both
     * silently, since every row is wrong by the same amount.
     */
    private val clock: () -> Long = { epochSeconds() + fleet.clockSkewSeconds.value },
    /** Where *By work* is remembered across launches; null keeps it for this run only. */
    private val prefs: Prefs? = null,
) {
    /** The four fleet flows combined into one value, so a second `combine` can fold in [local] and [now]. */
    private data class FleetSnapshot(
        val sessions: List<SessionRow>,
        val hosts: List<HostRow>,
        val projects: List<ProjectRow>,
        val status: ConnectionStatus,
    )

    private data class Local(
        val needsAttentionOnly: Boolean = false,
        val hostFilter: String? = null,
        val refreshing: Boolean = false,
        val error: Friendly? = null,
        val byWork: Boolean = false,
        val myWorkOnly: Boolean = false,
        val orgFilter: Long? = null,
    )

    /** What the hub's work graph adds to the picture: whether it is there, and *My work*. */
    /** [tickets] is the ticket cache by item id, overlaid on each row's work (see `withTicketsFrom`). */
    private data class Work(
        val available: Boolean,
        val myWork: Set<Long>?,
        val tickets: Map<Long, Ticket>,
        val orgs: OrgDirectory = OrgDirectory.EMPTY,
    )

    private val local = MutableStateFlow(Local(byWork = prefs?.getStringList(BY_WORK_KEY) == listOf(ON)))
    private val now = MutableStateFlow(clock())

    init {
        scope.launch {
            while (isActive) {
                delay(30_000)
                now.value = clock()
            }
        }
    }

    val state: StateFlow<SessionsUiState> = combine(
        combine(fleet.sessions, fleet.hosts, fleet.projects, fleet.status, ::FleetSnapshot),
        combine(fleet.capabilities, fleet.myWork, fleet.tickets, fleet.orgs) { caps, mine, cache, orgs ->
            Work(caps.work, mine, cache.associateBy { it.id }, orgs)
        },
        local,
        now,
    ) { snapshot, work, l, nowSeconds ->
        assemble(snapshot.sessions, snapshot.hosts, snapshot.projects, snapshot.status, work, l, nowSeconds)
    }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            // Computed rather than left blank: the flows are StateFlows, so the
            // first frame the screen draws can be the real one.
            assemble(
                fleet.sessions.value,
                fleet.hosts.value,
                fleet.projects.value,
                fleet.status.value,
                Work(
                    fleet.capabilities.value.work,
                    fleet.myWork.value,
                    fleet.tickets.value.associateBy { it.id },
                    fleet.orgs.value,
                ),
                local.value,
                now.value,
            ),
        )

    /**
     * Show only the rows that want a person, or stop. A view over rows already
     * held: this never talks to the hub.
     *
     * One method where there were two. The other — `setNeedsAttentionOnly(on)`
     * — had no caller in the app at all; the bar is wired to this one. Its only
     * callers were tests, so the path being exercised was not the path that
     * ships, which is the arrangement that lets a bug live in the difference
     * between them.
     *
     * The flip is one `update {}` rather than a read of `local.value` followed
     * by a write. Two taps cannot then read the same value and both write the
     * same answer, losing one — the rule `SessionViewModel`'s own KDoc states
     * for this exact shape, and the one `HostsViewModel` was already changed
     * for.
     */
    fun toggleNeedsAttentionOnly() {
        local.update { it.copy(needsAttentionOnly = !it.needsAttentionOnly) }
    }

    /**
     * Show only one host's groups, or all of them again with `null`. A view
     * over rows already held, like [toggleNeedsAttentionOnly] — this never
     * talks to the hub, and [SessionsUiState.attentionCount] stays fleet-wide
     * regardless of what this narrows [SessionsUiState.groups] to.
     */
    fun setHostFilter(alias: String?) {
        local.update { it.copy(hostFilter = alias) }
    }

    /**
     * Group each host's sessions by their work first, or stop. Remembered on
     * the device, because it is how a person reads the list rather than a
     * question they are asking this minute.
     */
    fun toggleByWork() {
        val next = local.updateAndGet { it.copy(byWork = !it.byWork) }.byWork
        prefs?.putStringList(BY_WORK_KEY, if (next) listOf(ON) else emptyList())
    }

    /** Only sessions on *My work* tickets, or all of them again. Never talks to the hub. */
    fun toggleMyWorkOnly() {
        local.update { it.copy(myWorkOnly = !it.myWorkOnly) }
    }

    /**
     * Only [org]'s sessions, or all of them again — tapping the chosen org
     * again clears it. Never talks to the hub: a phone's token already sees
     * every org, so this is a way of reading the list, not a scope.
     */
    fun toggleOrg(org: Long) {
        local.update { it.copy(orgFilter = if (it.orgFilter == org) null else org) }
    }

    /**
     * The org the list is narrowed to, as the screen applies it — what the
     * Today sheet scopes itself by, so the two never disagree.
     */
    val orgFilter: StateFlow<Long?> = state.map { it.orgFilter }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, state.value.orgFilter)

    /**
     * Clear the banner.
     *
     * Two of five screens had one and three did not, and the three without are
     * where an error can sit longest: a refresh that failed leaves its sentence
     * on screen until the *next* refresh succeeds, and on a hub that is down
     * that is never. The banner is not dangerous — the rows behind it are still
     * the last good picture — but an error a person has read and cannot put away
     * teaches them to stop reading the banner, which is the one thing it must
     * not do.
     */
    fun dismissError() {
        local.update { it.copy(error = null) }
    }
    /**
     * Re-list the fleet. The rows on screen stay put if it fails — the last
     * snapshot is still the best picture there is — and the failure is shown.
     *
     * A [ConnectionStatus.Refused] hub is not re-listed at all. The
     * repository already refuses to apply that hub's row events, so calling
     * its list tools would decode the very shape this build has said it
     * cannot read — and a spinner over three calls that end in the same
     * refusal is worse than a pull that does nothing behind a banner already
     * saying why.
     */
    fun refresh(): Job = scope.launch {
        if (fleet.status.value is ConnectionStatus.Refused) return@launch
        local.update { it.copy(refreshing = true, error = null) }
        try {
            fleet.refresh()
            local.update { it.copy(refreshing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(refreshing = false, error = friendly(t)) }
        }
    }

    private fun assemble(
        sessions: List<SessionRow>,
        hosts: List<HostRow>,
        projects: List<ProjectRow>,
        status: ConnectionStatus,
        work: Work,
        l: Local,
        nowSeconds: Long,
    ): SessionsUiState {
        // A hub that loses the work graph (a downgrade, a reconnect elsewhere)
        // takes the toggles with it rather than leaving a filter nobody can see.
        val byWork = work.available && l.byWork
        val myWork = work.myWork?.takeIf { work.available && l.myWorkOnly }
        val choices = orgChoices(sessions, work.orgs)
        // Like the toggles above: a filter nobody can see is dropped, so a
        // hub that stops listing a second org cannot leave the list narrowed.
        val org = l.orgFilter?.takeIf { f -> choices.any { it.id == f } }
        return SessionsUiState(
            groups = groupSessions(
                // Only a hub with the work graph has a ticket cache worth
                // overlaying; without one this is the rows as they came.
                if (work.available) sessions.map { it.withTicketsFrom(work.tickets) } else sessions,
                hosts,
                projects,
                l.needsAttentionOnly,
                l.hostFilter,
                byWork,
                myWork,
                org,
                orgLabel = if (choices.isNotEmpty() && org == null) work.orgs::name else null,
            ),
            status = status,
            needsAttentionOnly = l.needsAttentionOnly,
            hostFilter = l.hostFilter,
            attentionCount = sessions.count { it.needsAttention },
            refreshing = l.refreshing,
            error = l.error,
            nowSeconds = nowSeconds,
            workAvailable = work.available,
            byWork = byWork,
            myWorkAvailable = work.available && work.myWork != null,
            myWorkOnly = myWork != null,
            orgChoices = choices,
            orgFilter = org,
        )
    }

    private companion object {
        const val BY_WORK_KEY = "sessions.by_work"
        const val ON = "on"
    }
}

/**
 * The orgs [sessions] belong to, by name, when there are at least two — one
 * org, or none, is nothing to filter by. A session's org is its row's, else
 * its work's ([orgOf]).
 */
internal fun orgChoices(sessions: List<SessionRow>, orgs: OrgDirectory): List<OrgInfo> {
    val ids = sessions.mapNotNullTo(LinkedHashSet()) { it.orgOf }
    if (ids.size < 2) return emptyList()
    return ids.map { orgs.orgs[it] ?: OrgInfo(it, orgs.name(it)) }.sortedBy { it.name.lowercase() }
}

/** The label a project group carries, given what `list_projects` last said. */
private fun projectLabel(id: Long?, byId: Map<Long, ProjectRow>): String = when (id) {
    null -> NO_PROJECT
    else -> byId[id]?.label ?: unnamedProject(id)
}

/** The heading for sessions that belong to no project — a shell session, say. */
internal const val NO_PROJECT = "No project"

/**
 * Cut [sessions] into host groups and then project groups.
 *
 * Pure, so the shape of the list can be asserted without a scope or a clock.
 *
 * The order is fixed rather than inherited from the hub, because the hub's is
 * not stable across calls and a list that reshuffles under a thumb is worse
 * than one that is merely sorted oddly:
 *
 *  - hosts alphabetically;
 *  - projects alphabetically by label, with "no project at all" last, since it
 *    is a leftovers bin rather than a name;
 *  - sessions most recently active first, because that is the one being looked
 *    for. A row the hub has never stamped sorts last: it is not a row that just
 *    did something.
 *
 * Empty groups do not survive: when [needsAttentionOnly] leaves a host with
 * nothing, the host goes too, rather than drawing a heading over a blank. The
 * same is true of [hostFilter] — a host with nothing on it is simply absent,
 * not an empty heading.
 *
 * **[byWork]** puts each host's work groups ahead of its project groups —
 * hybrid, the desktop's `buildSessionsByWork` rule: only a session whose
 * *confirmed* primary link the hub stamped (`work`) joins a work group; a
 * suggestion alone, an external session, or no work at all leaves it in its
 * project, and there is no "Unclassified" bucket. Work groups come first by
 * how many of their sessions need a person, then by recency — the desktop's
 * `sortWorkGroups`, with attention as the severity.
 *
 * **[myWork]**, when set, keeps only sessions whose work is one of those
 * tracker items.
 *
 * **[org]**, when set, keeps only that org's sessions ([orgOf]); a session no
 * org claims is not in any. **[orgLabel]**, when set, names the org on each
 * work group's heading — for a list showing several orgs at once.
 */
internal fun groupSessions(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    needsAttentionOnly: Boolean,
    hostFilter: String? = null,
    byWork: Boolean = false,
    myWork: Set<Long>? = null,
    org: Long? = null,
    orgLabel: ((Long) -> String)? = null,
): List<HostGroup> {
    val attended = if (needsAttentionOnly) sessions.filter { it.needsAttention } else sessions
    val onHost = if (hostFilter != null) attended.filter { it.hostAlias == hostFilter } else attended
    val mine = if (myWork != null) onHost.filter { it.work?.itemId in myWork } else onHost
    val kept = if (org != null) mine.filter { it.orgOf == org } else mine
    if (kept.isEmpty()) return emptyList()

    val byId = projects.associateBy { it.id }
    val reachability = hosts.associate { it.alias to it.reachable }

    // `entries.sortedBy` rather than `toSortedMap()`: the latter is a JVM-only
    // extension and this file compiles for iOS too.
    return kept.groupBy { it.hostAlias }
        .entries
        .sortedBy { it.key }
        .map { (alias, rows) ->
            val (keyed, rest) = if (byWork) rows.partition { it.workGroupKey != null } else emptyList<SessionRow>() to rows
            HostGroup(
                alias = alias,
                reachable = reachability[alias],
                projects = workGroups(keyed, orgLabel) + rest.groupBy { it.projectId }
                    .map { (id, inProject) ->
                        ProjectGroup(
                            projectId = id,
                            label = projectLabel(id, byId),
                            sessions = inProject.sortedWith(BY_RECENCY),
                        )
                    }
                    // `null` last whatever it is called, then by label.
                    .sortedWith(compareBy({ it.projectId == null }, { it.label })),
            )
        }
}

/**
 * The key a session joins a work group under, or null when it stays in its
 * project: only the hub's confirmed primary link counts, never a guess, and a
 * session running outside fleet is never grouped.
 */
private val SessionRow.workGroupKey: String?
    get() = if (kind == "external") null else work?.groupKey

/** One host's keyed sessions as work groups, needing-a-person first, then by recency. */
private fun workGroups(keyed: List<SessionRow>, orgLabel: ((Long) -> String)?): List<ProjectGroup> =
    keyed.sortedWith(BY_RECENCY)
        .groupBy { it.workGroupKey!! }
        .map { (_, rows) ->
            val work = rows.first().work!!
            ProjectGroup(
                projectId = null,
                label = work.label,
                sessions = rows,
                work = work,
                orgLabel = orgLabel?.let { name -> rows.first().orgOf?.let(name) },
            )
        }
        // Stable: equal attention keeps the recency order the groupBy saw.
        .sortedByDescending { it.attentionCount }

/** Most recently active first; never-stamped rows last; ties broken by id. */
private val BY_RECENCY: Comparator<SessionRow> =
    compareByDescending<SessionRow> { it.lastActivityAt ?: Long.MIN_VALUE }.thenBy { it.id }
