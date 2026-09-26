package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgInfo
import dev.claudefleet.mobile.model.orgOf
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionFilters
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusFilter
import dev.claudefleet.mobile.model.TimeDirection
import dev.claudefleet.mobile.model.TimeWindow
import dev.claudefleet.mobile.model.byTriage
import dev.claudefleet.mobile.model.matches
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkStatusFilter
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

/**
 * How the list is shaped — a *view*, never a filter: each of these shows every
 * session that survived [SessionFilters], arranged differently.
 *
 * [URGENCY] is the one that is not a grouping at all. It is the desktop's
 * ranked queue (P13/P27), which there *replaced* the old stuck-only and
 * needs-attention pills because those "ordered rows two different ways": one
 * flat list, worst first, no host or project headings, so the session that
 * most wants a person is the first thing under the thumb. On a phone that
 * answers the question this screen exists for better than any arrangement of
 * filters can.
 */
enum class GroupMode(val label: String) {
    PROJECT("project"),
    WORK("work"),
    URGENCY("urgency"),
}

/**
 * A host the filter sheet offers, and whether the hub can reach it.
 *
 * Not `NewSessionViewModel`'s [HostChoice], whose `reachable` is a plain
 * `Boolean`: that list comes from `list_hosts`, where reachability is always
 * known, while this one also offers a host that only a *session* names. For
 * those the answer is **unknown**, and flattening it to `false` would draw a
 * working machine as unreachable — the accusation [HostGroup.reachable]'s own
 * KDoc exists to avoid. Same three words, different question.
 */
data class HostFilterChoice(val alias: String, val reachable: Boolean?)

/** A project the filter sheet offers: its id, and the label its group heading carries. */
data class ProjectFilterChoice(val id: Long, val label: String)

/** Everything the fleet list draws. */
data class SessionsUiState(
    val groups: List<HostGroup> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    /** Everything that narrows the list, in one value. */
    val filters: SessionFilters = SessionFilters(),
    /** How many rows in the **whole** fleet want a person, filtered or not. */
    val attentionCount: Int = 0,
    /**
     * How many sessions [groups] holds, and how many the fleet has in all.
     *
     * Drawn as "14 of 87" whenever the two differ, which is the one thing that
     * makes a filter's effect visible rather than leaving a person to wonder
     * where a session went. Every "why is my session missing" question this
     * screen can raise is answered by this line plus [SessionFilters.summary].
     */
    val shown: Int = 0,
    val total: Int = 0,
    val refreshing: Boolean = false,
    /** The last refresh's failure, in plain language with the hub's own words behind it. */
    val error: Friendly? = null,
    /** Unix seconds, refreshed every 30s by a ticker — what every row's age is computed against. */
    val nowSeconds: Long = 0,
    /** The hub has the work graph: the *By work* toggle is offered. */
    val workAvailable: Boolean = false,
    /** How the list is shaped. [GroupMode.WORK] is only ever set when [workAvailable]. */
    val groupMode: GroupMode = GroupMode.PROJECT,
    /**
     * The ranked queue, worst first — filled only in [GroupMode.URGENCY], where
     * [groups] is empty because the queue has no headings to group under.
     */
    val urgent: List<SessionRow> = emptyList(),
    /** The hub has a tracker and answered *My work*: the chip is offered. */
    val myWorkAvailable: Boolean = false,
    /**
     * The orgs the fleet's sessions belong to, by name — offered as a filter
     * only when there are two or more; empty hides it.
     */
    val orgChoices: List<OrgInfo> = emptyList(),
    /** Every host the sheet can narrow to: the host list, plus any host only a session names. */
    val hostChoices: List<HostFilterChoice> = emptyList(),
    /** Every project the sheet can narrow to: the ones the fleet's sessions are in, plus the chosen one. */
    val projectChoices: List<ProjectFilterChoice> = emptyList(),
    /** The filter sheet is up. */
    val filtersOpen: Boolean = false,
    /** The search field is showing (it holds [SessionFilters.query]). */
    val searchOpen: Boolean = false,
) {
    val isEmpty: Boolean get() = groups.isEmpty() && urgent.isEmpty()

    // The screen and its tests ask these of the state, not of the filters:
    // they were fields here before the filters were gathered into one value,
    // and there is no reason to make every caller reach through.
    /** Kept as it was before there were three modes: the screen and its tests ask this. */
    val byWork: Boolean get() = groupMode == GroupMode.WORK

    val needsAttentionOnly: Boolean get() = filters.needsAttentionOnly
    val hostFilter: String? get() = filters.hostFilter
    val myWorkOnly: Boolean get() = filters.myWorkOnly
    val orgFilter: Long? get() = filters.orgFilter

    /** The filters that are on, in words — the summary line and the empty state say the same thing. */
    fun filterNames(): List<String> = filters.summary(
        orgName = { id -> orgChoices.firstOrNull { it.id == id }?.name ?: "org #$id" },
        projectName = { id -> projectChoices.firstOrNull { it.id == id }?.label ?: unnamedProject(id) },
    )

    /** Rows are being hidden: what makes the "N of M" line worth drawing. */
    val narrowed: Boolean get() = filters.any && shown != total
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
        val filters: SessionFilters = SessionFilters(),
        /**
         * The instant the activity window is measured from — **not** [now].
         *
         * [now] re-emits every 30 seconds so every row's age stays honest, and
         * deriving window membership from it would mean rows silently leaving
         * the list under a thumb as they crossed the boundary mid-scroll: the
         * one thing a list must not do. So the window is anchored when a
         * person sets it and re-anchored when they pull to refresh, and
         * between those two the set of rows only changes because the fleet
         * did.
         */
        val filterAt: Long = 0,
        val refreshing: Boolean = false,
        val error: Friendly? = null,
        val groupMode: GroupMode = GroupMode.PROJECT,
        val filtersOpen: Boolean = false,
        val searchOpen: Boolean = false,
    )

    /** What the hub's work graph adds to the picture: whether it is there, and *My work*. */
    /** [tickets] is the ticket cache by item id, overlaid on each row's work (see `withTicketsFrom`). */
    private data class Work(
        val available: Boolean,
        val myWork: Set<Long>?,
        val tickets: Map<Long, Ticket>,
        val orgs: OrgDirectory = OrgDirectory.EMPTY,
    )

    private val local = MutableStateFlow(
        Local(groupMode = readGroupMode(), filterAt = clock()),
    )

    /**
     * The remembered view.
     *
     * `sessions.by_work` is still read, and still written, because a build
     * that has been storing `["on"]` for months is on people's phones: moving
     * to a new key alone would silently reset everyone's view to project on
     * upgrade. The new key wins when it is there; the old one is the fallback.
     */
    private fun readGroupMode(): GroupMode {
        prefs?.getStringList(GROUP_MODE_KEY)?.firstOrNull()?.let { stored ->
            GroupMode.entries.firstOrNull { it.name == stored }?.let { return it }
        }
        return if (prefs?.getStringList(BY_WORK_KEY) == listOf(ON)) GroupMode.WORK else GroupMode.PROJECT
    }
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
        filter { it.copy(needsAttentionOnly = !it.needsAttentionOnly) }
    }

    /**
     * Every filter through one place, so that no mutator can forget the parts
     * that are not its own field.
     *
     * Two of them: `update {}` rather than a read-then-write, which is the
     * rule [toggleNeedsAttentionOnly] states and which matters more now that
     * a sheet can put several of these in flight in the same frame; and
     * re-anchoring [Local.filterAt] on **every** change, so a window set an
     * hour after the app opened measures from now rather than from launch.
     */
    private fun filter(f: (SessionFilters) -> SessionFilters) {
        local.update { it.copy(filters = f(it.filters), filterAt = clock()) }
    }

    /** The search text. Blank searches for nothing and keeps every row. */
    fun setQuery(query: String) {
        filter { it.copy(query = query) }
    }

    /**
     * Show the search field, or hide it — and hiding it clears the query,
     * because a filter whose control is not on screen is one nobody can see to
     * undo. The summary line would still name it, but the field is where it is
     * turned off.
     */
    fun toggleSearch() {
        local.update {
            val open = !it.searchOpen
            it.copy(
                searchOpen = open,
                filters = if (open) it.filters else it.filters.copy(query = ""),
                filterAt = clock(),
            )
        }
    }

    /** How far back the activity window reaches. [TimeWindow.ANY] turns it off. */
    fun setWindow(window: TimeWindow) {
        filter { it.copy(window = window) }
    }

    /** Which side of the window to keep — recently active, or idle for longer than it. */
    fun setDirection(direction: TimeDirection) {
        filter { it.copy(direction = direction) }
    }

    /** Add [status] to the statuses kept, or drop it. No statuses chosen means every status. */
    fun toggleStatus(status: StatusFilter) {
        filter { f ->
            f.copy(statuses = if (status in f.statuses) f.statuses - status else f.statuses + status)
        }
    }

    /**
     * Only [projectId]'s sessions, or every project again with `null`. A view
     * over rows already held, like the host filter — and, unlike it, owned
     * here: no screen is scoped to a project.
     */
    fun setProjectFilter(projectId: Long?) {
        filter { it.copy(projectFilter = projectId) }
    }

    /** Add [status] to the ticket statuses kept, or drop it. None chosen means any. */
    fun toggleWorkStatus(status: WorkStatusFilter) {
        filter { f ->
            f.copy(workStatuses = if (status in f.workStatuses) f.workStatuses - status else f.workStatuses + status)
        }
    }

    /** List sessions archived from the desktop's Tidy-up, or leave them out. The desktop's `hide archived`. */
    fun toggleArchived() {
        filter { it.copy(showArchived = !it.showArchived) }
    }

    /** List background agents, or leave them out. The desktop's `bg on/off`. */
    fun toggleBackground() {
        filter { it.copy(showBackground = !it.showBackground) }
    }

    /**
     * Every filter off at once.
     *
     * The host filter is **not** cleared here: `Screen.Sessions.hostAlias`
     * owns it, and clearing it in this object alone leaves that screen value
     * stale, so opening a session and coming back resurrects it — the bug
     * `Navigator.clearHostFilter` exists for. The screen's *clear all* calls
     * both, and [SessionFilters.cleared] carries the host through so this one
     * cannot silently disagree with the navigator in the meantime.
     */
    fun clearFilters() {
        filter { it.cleared() }
        local.update { it.copy(searchOpen = false) }
    }

    /** Show the filter sheet, or put it away. */
    fun setFiltersOpen(open: Boolean) {
        local.update { it.copy(filtersOpen = open) }
    }

    /**
     * Show only one host's groups, or all of them again with `null`. A view
     * over rows already held, like [toggleNeedsAttentionOnly] — this never
     * talks to the hub, and [SessionsUiState.attentionCount] stays fleet-wide
     * regardless of what this narrows [SessionsUiState.groups] to.
     */
    fun setHostFilter(alias: String?) {
        filter { it.copy(hostFilter = alias) }
    }

    /**
     * Shape the list. Remembered on the device, because it is how a person
     * reads the list rather than a question they are asking this minute — and
     * unlike a filter it hides nothing, so restoring it on launch cannot make
     * a busy fleet look quiet.
     */
    fun setGroupMode(mode: GroupMode) {
        local.update { it.copy(groupMode = mode) }
        prefs?.putStringList(GROUP_MODE_KEY, listOf(mode.name))
        // The old key is kept in step so a downgrade still finds the view it
        // knows how to read. `urgency` has no representation there; it is not
        // `work`, so it writes the same thing `project` does.
        prefs?.putStringList(BY_WORK_KEY, if (mode == GroupMode.WORK) listOf(ON) else emptyList())
    }

    /**
     * The next view along, which is what tapping the header chip does — the
     * desktop's `group-by-toggle`, where the label always names the mode you
     * are *in* rather than the one you would get.
     *
     * Work is skipped on a hub without the work graph rather than offered and
     * ignored, so the cycle a person sees is the cycle they get.
     */
    fun cycleGroupMode(workAvailable: Boolean) {
        val modes = GroupMode.entries.filter { it != GroupMode.WORK || workAvailable }
        val next = modes[(modes.indexOf(local.value.groupMode).coerceAtLeast(0) + 1) % modes.size]
        setGroupMode(next)
    }

    /** Only sessions on *My work* tickets, or all of them again. Never talks to the hub. */
    fun toggleMyWorkOnly() {
        filter { it.copy(myWorkOnly = !it.myWorkOnly) }
    }

    /**
     * Only [org]'s sessions, or all of them again — tapping the chosen org
     * again clears it. Never talks to the hub: a phone's token already sees
     * every org, so this is a way of reading the list, not a scope.
     */
    fun toggleOrg(org: Long) {
        filter { it.copy(orgFilter = if (it.orgFilter == org) null else org) }
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
            // A pull is the other thing that re-anchors the activity window
            // (see `Local.filterAt`): asking for the list again is asking for
            // "within 8 hours" to mean eight hours from *now*.
            local.update { it.copy(refreshing = false, error = null, filterAt = clock()) }
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
        // A hub that loses the work graph cannot leave the list in a mode
        // whose chip is gone, the same rule the filters follow below.
        val groupMode = if (l.groupMode == GroupMode.WORK && !work.available) GroupMode.PROJECT else l.groupMode
        val byWork = groupMode == GroupMode.WORK
        val myWorkAvailable = work.available && work.myWork != null
        val choices = orgChoices(sessions, work.orgs)
        // Like the toggles above: a filter nobody can see is dropped, so a
        // hub that stops listing a second org cannot leave the list narrowed,
        // and neither can a *My work* filter whose tracker went away.
        val filters = l.filters.copy(
            myWorkOnly = l.filters.myWorkOnly && myWorkAvailable,
            orgFilter = l.filters.orgFilter?.takeIf { f -> choices.any { it.id == f } },
            // The ticket filters read the work graph's fields; a hub without
            // it has no control for them on screen, so they are off.
            workStatuses = if (work.available) l.filters.workStatuses else emptySet(),
            showArchived = l.filters.showArchived || !work.available,
        )
        val myWork = work.myWork?.takeIf { filters.myWorkOnly }
        // Only a hub with the work graph has a ticket cache worth overlaying;
        // without one these are the rows as they came.
        val rows = if (work.available) sessions.map { it.withTicketsFrom(work.tickets) } else sessions
        val urgency = groupMode == GroupMode.URGENCY
        // The queue is flat, so it is filtered here rather than grouped: the
        // narrowing is the same `matches` the tree uses, with the same project
        // label handed in, so the two views can never keep different rows.
        val byId = projects.associateBy { it.id }
        val urgent = if (!urgency) {
            emptyList()
        } else {
            rows.filter { row ->
                row.matches(filters, l.filterAt, projectLabel(row.projectId, byId)) &&
                    (myWork == null || row.work?.itemId in myWork)
            }.byTriage(now = nowSeconds)
        }
        val groups = if (urgency) {
            emptyList()
        } else {
            groupSessions(
                rows,
                hosts,
                projects,
                filters,
                l.filterAt,
                byWork,
                myWork,
                orgLabel = if (choices.isNotEmpty() && filters.orgFilter == null) work.orgs::name else null,
            )
        }
        return SessionsUiState(
            groups = groups,
            status = status,
            filters = filters,
            attentionCount = sessions.count { it.needsAttention },
            shown = if (urgency) urgent.size else groups.sumOf { it.sessionCount },
            total = sessions.size,
            refreshing = l.refreshing,
            error = l.error,
            nowSeconds = nowSeconds,
            workAvailable = work.available,
            groupMode = groupMode,
            urgent = urgent,
            myWorkAvailable = myWorkAvailable,
            orgChoices = choices,
            hostChoices = hostChoices(sessions, hosts),
            projectChoices = projectChoices(sessions, projects, filters.projectFilter),
            filtersOpen = l.filtersOpen,
            searchOpen = l.searchOpen,
        )
    }

    private companion object {
        const val BY_WORK_KEY = "sessions.by_work"
        const val GROUP_MODE_KEY = "sessions.group_mode"
        const val ON = "on"
    }
}

/**
 * Every host the filter sheet can narrow to, alphabetically.
 *
 * The host list is the source, plus any alias only a session names — the same
 * reasoning `HostGroup.reachable` documents: a host missing from `list_hosts`
 * is unknown rather than absent, and leaving it out of the sheet would make
 * its sessions unreachable by the one filter meant to find them. A hidden host
 * with no sessions stays hidden; one with sessions is offered, because its
 * rows are on screen either way.
 */
internal fun hostChoices(sessions: List<SessionRow>, hosts: List<HostRow>): List<HostFilterChoice> {
    val named = sessions.mapTo(LinkedHashSet()) { it.hostAlias }
    val known = hosts.filter { !it.hidden || it.alias in named }.map { HostFilterChoice(it.alias, it.reachable) }
    val extra = (named - known.mapTo(mutableSetOf()) { it.alias }).map { HostFilterChoice(it, null) }
    return (known + extra).filter { it.alias.isNotBlank() }.sortedBy { it.alias }
}

/**
 * Every project the filter sheet can narrow to, by label: the ones [sessions]
 * are in, plus [chosen] even once its last session has gone — the host
 * filter's rule, so a filter that is on always has a chip on screen to turn it
 * off. Sessions in no project are not offered: "no project" is a leftovers
 * bin, and the search field finds a shell session by name.
 */
internal fun projectChoices(
    sessions: List<SessionRow>,
    projects: List<ProjectRow>,
    chosen: Long? = null,
): List<ProjectFilterChoice> {
    val byId = projects.associateBy { it.id }
    val ids = sessions.mapNotNullTo(LinkedHashSet()) { it.projectId }
    chosen?.let(ids::add)
    return ids.map { ProjectFilterChoice(it, projectLabel(it, byId)) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.id }))
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
 * Empty groups do not survive: when a filter leaves a host with nothing, the
 * host goes too, rather than drawing a heading over a blank. The same is true
 * of [SessionFilters.hostFilter] — a host with nothing on it is simply absent,
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
 * tracker items. It is not part of [filters] because it is the hub's answer
 * rather than the person's question: the toggle is
 * [SessionFilters.myWorkOnly], and this is what it resolves to.
 *
 * **[orgLabel]**, when set, names the org on each work group's heading — for a
 * list showing several orgs at once.
 *
 * Narrowing is [SessionRow.matches] and lives with the filters, not here, so
 * that the rule can be read and tested without a host tree around it. The
 * project label goes in with each row because a person typing a repo name
 * expects to find its sessions, and a row carries only a `project_id`.
 */
internal fun groupSessions(
    sessions: List<SessionRow>,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    filters: SessionFilters = SessionFilters(),
    nowSeconds: Long = 0,
    byWork: Boolean = false,
    myWork: Set<Long>? = null,
    orgLabel: ((Long) -> String)? = null,
): List<HostGroup> {
    val byId = projects.associateBy { it.id }
    val kept = sessions.filter { row ->
        row.matches(filters, nowSeconds, projectLabel(row.projectId, byId)) &&
            (myWork == null || row.work?.itemId in myWork)
    }
    if (kept.isEmpty()) return emptyList()

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
