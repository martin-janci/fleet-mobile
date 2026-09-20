package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.unnamedProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sessions of one project on one host. */
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
) {
    val sessionCount: Int get() = projects.sumOf { it.sessions.size }
}

/** Everything the fleet list draws. */
data class SessionsUiState(
    val groups: List<HostGroup> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    val needsAttentionOnly: Boolean = false,
    /** How many rows in the **whole** fleet want a person, filtered or not. */
    val attentionCount: Int = 0,
    val refreshing: Boolean = false,
    /** The last refresh's failure, in the hub's own words. */
    val error: String? = null,
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
) {
    private data class Local(
        val needsAttentionOnly: Boolean = false,
        val refreshing: Boolean = false,
        val error: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SessionsUiState> = combine(
        fleet.sessions,
        fleet.hosts,
        fleet.projects,
        fleet.status,
        local,
    ) { sessions, hosts, projects, status, l -> assemble(sessions, hosts, projects, status, l) }
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
     */
    fun refresh(): Job = scope.launch {
        local.update { it.copy(refreshing = true, error = null) }
        try {
            fleet.refresh()
            local.update { it.copy(refreshing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(refreshing = false, error = explain(t)) }
        }
    }

    private fun assemble(
        sessions: List<SessionRow>,
        hosts: List<HostRow>,
        projects: List<ProjectRow>,
        status: ConnectionStatus,
        l: Local,
    ) = SessionsUiState(
        groups = groupSessions(sessions, hosts, projects, l.needsAttentionOnly),
        status = status,
        needsAttentionOnly = l.needsAttentionOnly,
        attentionCount = sessions.count { it.needsAttention },
        refreshing = l.refreshing,
        error = l.error,
    )
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
 * nothing, the host goes too, rather than drawing a heading over a blank.
 */
internal fun groupSessions(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    needsAttentionOnly: Boolean,
): List<HostGroup> {
    val kept = if (needsAttentionOnly) sessions.filter { it.needsAttention } else sessions
    if (kept.isEmpty()) return emptyList()

    val byId = projects.associateBy { it.id }
    val reachability = hosts.associate { it.alias to it.reachable }

    // `entries.sortedBy` rather than `toSortedMap()`: the latter is a JVM-only
    // extension and this file compiles for iOS too.
    return kept.groupBy { it.hostAlias }
        .entries
        .sortedBy { it.key }
        .map { (alias, rows) ->
            HostGroup(
                alias = alias,
                reachable = reachability[alias],
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
