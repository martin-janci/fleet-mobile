package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.unnamedProject
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The sessions of one project on one host. */
data class ProjectGroup(
    /** Null for sessions that belong to no project at all — a shell session. */
    val projectId: Long?,
    val label: String,
    val sessions: List<SessionRow>,
)

/**
 * The sessions of one host working on one piece of work — the hub's confirmed
 * `work` link, never a suggestion (work graph M8.2). Drawn above the host's
 * project groups when grouping by work is on.
 */
data class WorkGroup(
    /** The key the sessions share (`PAY-7`), or the title for a keyless item. */
    val key: String,
    /** The ticket's title; empty for a bare key the hub has no ticket for. */
    val title: String,
    val status: StatusCategory?,
    /** The tracker stopped answering for the item: drawn struck through. */
    val unavailable: Boolean,
    val sessions: List<SessionRow>,
) {
    /** How many of [sessions] want a person — the group's first number. */
    val attentionCount: Int get() = sessions.count { it.needsAttention }
}

/** One machine's sessions: its work groups, then the rest cut into projects. */
data class HostGroup(
    val alias: String,
    /**
     * What `list_hosts` said. **Null means unknown**, not unreachable: a session
     * row names its host, and the host list may not have arrived yet or may not
     * contain it at all.
     */
    val reachable: Boolean?,
    val projects: List<ProjectGroup>,
    /** Empty unless grouping by work; a session is in one of these or a project, never both. */
    val work: List<WorkGroup> = emptyList(),
) {
    val sessionCount: Int get() = projects.sumOf { it.sessions.size } + work.sumOf { it.sessions.size }
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
    /** The hub serves `work`: the "by work" toggle and the row chips are shown. */
    val workAvailable: Boolean = false,
    /** Host groups lead with work groups. Remembered on the device. */
    val byWork: Boolean = false,
    /** A tracker is connected and "My work" answered: the chip is shown. */
    val myWorkAvailable: Boolean = false,
    /** Only sessions linked to one of the person's own tickets. */
    val myWorkOnly: Boolean = false,
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
    /** The work-graph reads; null leaves "My work" off, as against an old hub. */
    private val work: WorkActions? = null,
    /** Where the "by work" toggle is remembered; null forgets it with the screen. */
    private val prefs: Prefs? = null,
    /**
     * The clock every age on this screen is measured against: the device's,
     * corrected by what the hub said its own time was on the last `ready`.
     * Uncorrected, a device a few minutes behind labels the whole fleet "just
     * now" and one running fast labels a working session as hours idle — both
     * silently, since every row is wrong by the same amount.
     */
    private val clock: () -> Long = { epochSeconds() + fleet.clockSkewSeconds.value },
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
    )

    /**
     * What "My work" is made of. [itemIds] is null until `tickets(mine)` has
     * answered once: an unknown set hides the chip rather than filtering the
     * list down to nothing.
     */
    private data class MyWork(val hasTracker: Boolean = false, val itemIds: Set<Long>? = null)

    /** The work-graph inputs, combined into one value for the same reason as [FleetSnapshot]. */
    private data class WorkInputs(
        val capabilities: HubCapabilities,
        val tickets: Map<Long, Ticket>,
        val myWork: MyWork,
    )

    private val local = MutableStateFlow(Local(byWork = prefs?.getStringList(BY_WORK_KEY)?.firstOrNull() == "true"))
    private val now = MutableStateFlow(clock())
    private val myWork = MutableStateFlow(MyWork())

    init {
        scope.launch {
            while (isActive) {
                delay(30_000)
                now.value = clock()
            }
        }
        // Every `ready` re-reads the hub's tools; a hub that gains (or is
        // found to serve) `work` loads "My work" once, and a later refresh
        // reloads it. An action marked refused changes nothing here.
        scope.launch {
            fleet.capabilities
                .distinctUntilChangedBy { it.copy(refused = emptyMap()) }
                .collect { if (it.work) loadMyWork() }
        }
    }

    val state: StateFlow<SessionsUiState> = combine(
        combine(fleet.sessions, fleet.hosts, fleet.projects, fleet.status, ::FleetSnapshot),
        local,
        now,
        combine(fleet.capabilities, fleet.tickets, myWork, ::WorkInputs),
    ) { snapshot, l, nowSeconds, inputs ->
        assemble(snapshot.sessions, snapshot.hosts, snapshot.projects, snapshot.status, l, nowSeconds, inputs)
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
                local.value,
                now.value,
                WorkInputs(fleet.capabilities.value, fleet.tickets.value, myWork.value),
            ),
        )

    /**
     * Lead each host with its work groups, or stop. Remembered on this device
     * — it is how a person reads the list, not a filter they reach for.
     */
    fun toggleByWork() {
        val on = local.updateAndGet { it.copy(byWork = !it.byWork) }.byWork
        prefs?.putStringList(BY_WORK_KEY, if (on) listOf("true") else emptyList())
    }

    /** Show only sessions on the person's own tickets, or stop. Never talks to the hub. */
    fun toggleMyWorkOnly() {
        local.update { it.copy(myWorkOnly = !it.myWorkOnly) }
    }

    /**
     * Ask the hub whether a tracker is connected and, if so, which tickets are
     * the person's. A failure keeps what was known rather than hiding a chip
     * that was working a moment ago; the rows themselves never depend on it.
     */
    private suspend fun loadMyWork() {
        val actions = work ?: return
        try {
            if (actions.trackers().isEmpty()) {
                myWork.value = MyWork(hasTracker = false)
                return
            }
            val mine = actions.tickets(MY_WORK_VIEW)
            fleet.remember(mine)
            myWork.value = MyWork(hasTracker = true, itemIds = mine.mapTo(mutableSetOf()) { it.id })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Kept as it was: see the KDoc.
        }
    }

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
            if (fleet.capabilities.value.work) loadMyWork()
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
        l: Local,
        nowSeconds: Long,
        inputs: WorkInputs,
    ): SessionsUiState {
        val workAvailable = inputs.capabilities.work
        val mine = inputs.myWork.itemIds?.takeIf { workAvailable && inputs.myWork.hasTracker }
        val byWork = l.byWork && workAvailable
        val myWorkOnly = l.myWorkOnly && mine != null
        return SessionsUiState(
            groups = groupSessions(
                sessions,
                hosts,
                projects,
                l.needsAttentionOnly,
                l.hostFilter,
                byWork = byWork,
                myWorkItemIds = if (myWorkOnly) mine else null,
                tickets = inputs.tickets,
            ),
            status = status,
            needsAttentionOnly = l.needsAttentionOnly,
            hostFilter = l.hostFilter,
            attentionCount = sessions.count { it.needsAttention },
            refreshing = l.refreshing,
            error = l.error,
            nowSeconds = nowSeconds,
            workAvailable = workAvailable,
            byWork = byWork,
            myWorkAvailable = mine != null,
            myWorkOnly = myWorkOnly,
        )
    }

    private companion object {
        const val BY_WORK_KEY = "sessions_by_work"
        const val MY_WORK_VIEW = "mine"
    }
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
 * **By work** ([byWork]) is hybrid, as on the desktop (`buildSessionsByWork`
 * in claude-fleet's `sidebar_index.ts`): a session whose hub-stamped `work` is
 * a confirmed link goes into that key's [WorkGroup] on its host, whatever
 * project it is in; every other session — a suggestion only, no link, or an
 * `external` session — stays in its project group. There is no
 * "Unclassified" bucket: ad-hoc work is not a defect. Work groups come first,
 * those with someone waiting first, then the most recently active; the
 * phone never derives a key itself (the hub stamps `work` on the row).
 * [tickets] overlays the ticket cache's newer status and title on the
 * group, since a `work:item` frame does not restamp session rows.
 *
 * **My work** ([myWorkItemIds]) keeps only sessions linked to one of those
 * items; null is "not filtering".
 */
internal fun groupSessions(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    needsAttentionOnly: Boolean,
    hostFilter: String? = null,
    byWork: Boolean = false,
    myWorkItemIds: Set<Long>? = null,
    tickets: Map<Long, Ticket> = emptyMap(),
): List<HostGroup> {
    val attended = if (needsAttentionOnly) sessions.filter { it.needsAttention } else sessions
    val onHost = if (hostFilter != null) attended.filter { it.hostAlias == hostFilter } else attended
    val kept = if (myWorkItemIds != null) {
        onHost.filter { row -> row.work?.itemId?.let { it in myWorkItemIds } == true }
    } else {
        onHost
    }
    if (kept.isEmpty()) return emptyList()

    val byId = projects.associateBy { it.id }
    val reachability = hosts.associate { it.alias to it.reachable }

    // `entries.sortedBy` rather than `toSortedMap()`: the latter is a JVM-only
    // extension and this file compiles for iOS too.
    return kept.groupBy { it.hostAlias }
        .entries
        .sortedBy { it.key }
        .map { (alias, all) ->
            val (keyed, rows) = if (byWork) all.partition { workKeyOf(it) != null } else emptyList<SessionRow>() to all
            HostGroup(
                alias = alias,
                reachable = reachability[alias],
                work = keyed.groupBy { workKeyOf(it)!! }
                    .map { (key, inWork) -> workGroup(key, inWork.sortedWith(BY_RECENCY), tickets) }
                    .sortedWith(WORK_GROUP_ORDER),
                projects = rows.groupBy { it.projectId }
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

/** Most recently active first; never-stamped rows last; ties broken by id. */
private val BY_RECENCY: Comparator<SessionRow> =
    compareByDescending<SessionRow> { it.lastActivityAt ?: Long.MIN_VALUE }.thenBy { it.id }

/**
 * The key a session groups under by work, or null to leave it in its project:
 * the hub's confirmed link only. A suggestion rides `work_suggested` and never
 * reaches here; the state check is for a hub that ever put one in `work`.
 */
private fun workKeyOf(row: SessionRow): String? {
    if (row.kind == "external") return null
    val work = row.work ?: return null
    if (work.isSuggestion) return null
    return work.label.takeIf { it.isNotBlank() }
}

/** A group's heading, from its freshest row, with the ticket cache's newer word on status and title. */
private fun workGroup(key: String, sessions: List<SessionRow>, tickets: Map<Long, Ticket>): WorkGroup {
    val work = sessions.first().work!!
    val ticket = work.itemId?.let { tickets[it] }
    return WorkGroup(
        key = key,
        title = ticket?.title?.takeIf { it.isNotBlank() } ?: work.title,
        status = ticket?.statusCategory ?: work.statusCategory,
        unavailable = ticket?.unavailable ?: work.unavailable,
        sessions = sessions,
    )
}

/**
 * Someone waiting first — the desktop sorts by worst severity; the phone's
 * one severity is "needs a person" — then the most recently active (the
 * desktop's tie order), then the key so the order never flickers.
 */
private val WORK_GROUP_ORDER: Comparator<WorkGroup> =
    compareByDescending<WorkGroup> { it.attentionCount > 0 }
        .thenByDescending { g -> g.sessions.maxOf { it.lastActivityAt ?: Long.MIN_VALUE } }
        .thenBy { it.key }
