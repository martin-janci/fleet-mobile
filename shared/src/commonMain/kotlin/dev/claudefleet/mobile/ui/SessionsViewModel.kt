package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.utcOffsetSeconds
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.Facet
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.orgColorArgb
import dev.claudefleet.mobile.model.OrgInfo
import dev.claudefleet.mobile.model.orgOf
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionFacetId
import dev.claudefleet.mobile.model.SessionFilters
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusFilter
import dev.claudefleet.mobile.model.TimeDirection
import dev.claudefleet.mobile.model.TimeWindow
import dev.claudefleet.mobile.model.byTriage
import dev.claudefleet.mobile.model.triageBucket
import dev.claudefleet.mobile.model.matches
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.model.withTicketsFrom
import dev.claudefleet.mobile.model.sessionFacets
import dev.claudefleet.mobile.model.unnamedProject
import dev.claudefleet.mobile.model.without
import dev.claudefleet.mobile.model.workStatusNames
import dev.claudefleet.mobile.net.json
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
import dev.claudefleet.mobile.model.relativeTime

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
    /**
     * Whether the person has folded this host away.
     *
     * A *drawing* decision, not a narrowing one: [projects] is still built and
     * [sessionCount] still counts, and `SessionsScreen` is what skips emitting
     * the items. Emptying the group here instead would make the count read
     * zero on exactly the headings whose count is the only thing left to say —
     * which is the one number that would stop anyone unfolding it again.
     */
    val collapsed: Boolean = false,
) {
    val sessionCount: Int get() = projects.sumOf { it.sessions.size }

    /** How many of its sessions need a person — said on the heading, folded or not. */
    val attentionCount: Int get() = projects.sumOf { p -> p.sessions.count { it.needsAttention } }
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

    /**
     * Hosts with their sessions straight under them, no project headings —
     * the New layout's "Group: host" (MobileNav). Offered by the New
     * layout's filter sheet only; the Classic chip's cycle skips it, so a
     * Classic list never lands here unless New chose it.
     */
    HOST("host"),
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
     * How many rows that want a person are **not on screen** because of the
     * current filters.
     *
     * The one number that makes a remembered filter safe to restore. A window
     * set on Friday would otherwise open Monday's app on a fleet that looks
     * calm while three agents sit blocked behind it — the objection
     * [SessionsViewModel.setGroupMode]'s KDoc raises against storing filters at
     * all. Counting them is the answer: the quiet fleet cannot be a lie when
     * the app is the thing saying it is not one.
     *
     * Deliberately *not* "how many rows did the filter hide" — that is already
     * on screen as [shown] against [total]. Only the rows that stop until
     * somebody moves them count, or the warning fires on every ordinary
     * narrowing and is learnt to be ignored.
     */
    val hiddenAttention: Int = 0,
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
    /**
     * Sessions that need a person, worst first, pinned above the groups in
     * the project and work views — so one waiting on a host at the bottom of
     * the alphabet, or on a folded host, is the first thing seen. Empty in
     * the urgency view (it already is that order) and under *Needs you*
     * (every row is one).
     */
    val pinned: List<SessionRow> = emptyList(),
    /** Never yet connected, nothing to show: "connecting", not "no sessions". */
    val connecting: Boolean = false,
    /** While not live: how old the rows on screen are ("12 min"); null when live or never. */
    val staleFor: String? = null,
    /** While not live: the time of day the rows on screen were last live ("14:52"), for the New bar's banner; null when live or never. */
    val staleAt: String? = null,
    /** The hub has a tracker and answered *My work*: the chip is offered. */
    val myWorkAvailable: Boolean = false,
    /**
     * The orgs the fleet's sessions belong to, by name — offered as a filter
     * only when there are two or more; empty hides it.
     */
    val orgChoices: List<OrgInfo> = emptyList(),
    /** Account uuid → label, for a row's account chip and a paused row's line (step 4.10). */
    val accountNames: Map<String, String> = emptyMap(),
    /** Account uuid → its usage reading, for when a paused row's limit resets (step 4.10). */
    val accountUsage: Map<String, AccountUsageSnapshot> = emptyMap(),
    /**
     * Org id → the org's colour (opaque ARGB) for the bar at a row's edge
     * (claude-fleet M10.5), filled only when [orgChoices] is: with one org or
     * none, a colour tells nothing apart. An org with no colour, or one that is
     * not `#rgb` / `#rrggbb`, has no entry and draws no bar.
     */
    val orgColors: Map<Long, Long> = emptyMap(),
    /** Every host the sheet can narrow to: the host list, plus any host only a session names. */
    val hostChoices: List<HostFilterChoice> = emptyList(),
    /** Every project the sheet can narrow to: the ones the fleet's sessions are in, plus the chosen one. */
    val projectChoices: List<ProjectFilterChoice> = emptyList(),
    /** The filter sheet is up. */
    val filtersOpen: Boolean = false,
    /** The search field is showing (it holds [SessionFilters.query]). */
    val searchOpen: Boolean = false,
    /** The tracker status names the sheet offers beside the three buckets ([workStatusNames]). */
    val workStatusNameChoices: List<String> = emptyList(),
    /**
     * Sessions that pass every other filter but are hidden because they are
     * archived ([SessionFilters.showArchived] is off by default): what the
     * *N archived sessions hidden · Show archived* row at the end of the list
     * counts. 0 while they are shown.
     */
    val archivedHidden: Int = 0,
    /** How many of [archivedHidden] want a person — said on that row, since the banner does not count them. */
    val archivedAttention: Int = 0,
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

    /**
     * Every filter that narrows the list, in the desktop's words
     * ([sessionFacets]) — what the strip draws and the empty state names.
     */
    val facets: List<Facet<SessionFacetId>>
        get() = sessionFacets(
            filters,
            orgName = { id -> orgChoices.firstOrNull { it.id == id }?.name },
            projectName = { id -> projectChoices.firstOrNull { it.id == id }?.label },
        )

    /** The strip's chips: [facets] less the ones with a control of their own on screen (Needs you, the search). */
    val stripFacets: List<Facet<SessionFacetId>> get() = facets.filterNot { it.id.onScreen }

    /** The filters that are on, in words — the strip and the empty state say the same thing. */
    fun filterNames(): List<String> = facets.map { it.label }

    /** Rows are being hidden: what makes the "N of M" line worth drawing. */
    val narrowed: Boolean get() = filters.any && shown != total

    /** The archived row at the end of the list is drawn: some are hidden, or they are shown and can be hidden again. */
    val archivedRow: Boolean get() = workAvailable && (archivedHidden > 0 || filters.showArchived)
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
    /** The device's offset from UTC at a moment, for [SessionsUiState.staleAt]'s time of day. */
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
) {
    /** The four fleet flows combined into one value, so a second `combine` can fold in [local] and [now]. */
    private data class FleetSnapshot(
        val sessions: List<SessionRow>,
        val hosts: List<HostRow>,
        val projects: List<ProjectRow>,
        val status: ConnectionStatus,
    )

    private data class Local(
        /** When the stream was last live: null until it first was — what is on screen then is not the fleet yet. */
        val liveAt: Long? = null,
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
        /** Hosts the person has folded away. Persisted; see [COLLAPSED_KEY]. */
        val collapsedHosts: Set<String> = emptySet(),
    )

    /** What the hub's work graph adds to the picture: whether it is there, and *My work*. */
    /** [tickets] is the ticket cache by item id, overlaid on each row's work (see `withTicketsFrom`). */
    private data class Work(
        val available: Boolean,
        val myWork: Set<Long>?,
        val tickets: Map<Long, Ticket>,
        val orgs: OrgDirectory = OrgDirectory.EMPTY,
        val accountNames: Map<String, String> = emptyMap(),
        val accountUsage: Map<String, AccountUsageSnapshot> = emptyMap(),
    )

    private val local = MutableStateFlow(
        Local(
            groupMode = readGroupMode(),
            filters = readFilters(),
            filterAt = clock(),
            collapsedHosts = prefs?.getStringList(COLLAPSED_KEY).orEmpty().toSet(),
        ),
    )

    /**
     * The remembered filters, or none at all.
     *
     * Anything the build cannot read is *none*: the store outlives the version
     * that wrote it, so a downgrade, or a build that has dropped a field, must
     * not crash on the first frame with a value it put there itself. Opening on
     * the whole fleet is the safe direction to be wrong in — it shows more than
     * asked rather than less.
     *
     * It does **not** strip [SessionFilters.query] or
     * [SessionFilters.hostFilter] a second time. [remember] is where that rule
     * lives and the only place it is written, because a rule implemented twice
     * is a rule neither copy is tested for: a mutation sweep removed each side
     * in turn and the suite stayed green both times, since the other half was
     * still doing the work.
     */
    private fun readFilters(): SessionFilters {
        prefs?.getStringList(FILTERS_KEY)?.firstOrNull()?.let { stored ->
            return decodeFilters(stored) ?: SessionFilters()
        }
        // Before archived sessions were hidden by default. Every install
        // wrote `showArchived: true` there — the old default, since the store
        // encodes defaults — so it says nothing about a choice: carry the
        // rest over and start hidden, as the desktop's `readWorkFilters` does.
        val old = prefs?.getStringList(FILTERS_KEY_V1)?.firstOrNull() ?: return SessionFilters()
        return decodeFilters(old)?.copy(showArchived = false) ?: SessionFilters()
    }

    private fun decodeFilters(stored: String): SessionFilters? = try {
        json.decodeFromString(SessionFilters.serializer(), stored)
    } catch (_: Exception) {
        null
    }

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
            // Entering *and* leaving a live stream: the rows were current up
            // to the moment it dropped, so that is what their age counts from.
            var wasLive = false
            fleet.status.collect { status ->
                val live = status is ConnectionStatus.Connected
                if (live || wasLive) local.update { it.copy(liveAt = clock()) }
                wasLive = live
            }
        }
        scope.launch {
            while (isActive) {
                delay(30_000)
                now.value = clock()
            }
        }
    }

    val state: StateFlow<SessionsUiState> = combine(
        combine(fleet.sessions, fleet.hosts, fleet.projects, fleet.status, ::FleetSnapshot),
        combine(
            fleet.capabilities,
            fleet.myWork,
            fleet.tickets,
            fleet.orgs,
            combine(fleet.accountNames, fleet.accountUsage, ::Pair),
        ) { caps, mine, cache, orgs, (accounts, usage) ->
            Work(caps.work, mine, cache.associateBy { it.id }, orgs, accounts, usage)
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
                    fleet.accountNames.value,
                    fleet.accountUsage.value,
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
        var updated = SessionFilters()
        local.update {
            updated = f(it.filters)
            it.copy(filters = updated, filterAt = clock())
        }
        remember(updated)
    }

    /**
     * Write the filters to the device, less the two that must not survive a
     * launch.
     *
     * [SessionFilters.query] is a moment rather than a setting — an app
     * reopened on Monday still filtered to something typed on Friday reads as
     * broken, which is why `setSearchOpen(false)` already clears it within a
     * session. [SessionFilters.hostFilter] is not a judgement call at all:
     * `Screen.Sessions.hostAlias` is its one source of truth, so a stored copy
     * would come back disagreeing with the navigator — the bug
     * `Navigator.clearHostFilter` exists to prevent.
     */
    private fun remember(filters: SessionFilters) {
        val storable = filters.copy(query = "", hostFilter = null)
        prefs?.putStringList(FILTERS_KEY, listOf(json.encodeToString(SessionFilters.serializer(), storable)))
    }

    /** The search text. Blank searches for nothing and keeps every row. */
    fun setQuery(query: String) {
        filter { it.copy(query = query) }
    }

    /**
     * Show the search field, or hide it — and hiding it clears the query,
     * because a filter whose control is not on screen is one nobody can see to
     * undo. The empty state would still name it, but the field is where it is
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

    /** Add the tracker status [name] ("QA Review") to the ticket statuses kept, or drop it. */
    fun toggleWorkStatusName(name: String) {
        filter { f ->
            val chosen = f.workStatusNames.firstOrNull { it.equals(name, ignoreCase = true) }
            f.copy(workStatusNames = if (chosen != null) f.workStatusNames - chosen else f.workStatusNames + name)
        }
    }

    /** List sessions archived from the desktop's Tidy-up, or leave them out (the default). */
    fun toggleArchived() {
        filter { it.copy(showArchived = !it.showArchived) }
    }

    /** The archived row's *Show archived* / *Hide archived*. */
    fun setShowArchived(show: Boolean) {
        filter { it.copy(showArchived = show) }
    }

    /**
     * One chip's ✕, or a sheet group's *Any*: that filter back to its
     * default, the rest kept. [SessionFacetId.HOST] is cleared here too, but
     * the navigator owns it, so the screen clears `Screen.Sessions.hostAlias`
     * alongside (`Navigator.clearHostFilter`), exactly as *Clear all* does.
     * Clearing the search also puts the field away, as [toggleSearch] would.
     */
    fun clearFacet(id: SessionFacetId) {
        filter { it.without(id) }
        if (id == SessionFacetId.SEARCH) local.update { it.copy(searchOpen = false) }
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
     * Fold one host's rows away, or unfold them.
     *
     * A view over rows already held: this never talks to the hub, and the
     * host's heading and [HostGroup.sessionCount] stay whatever they were.
     * Folding is not filtering — "which sessions exist on mefistos" and "show
     * me none of them right now" are different questions, and this answers only
     * the second.
     *
     * Remembered on the device for the same reason [setGroupMode] is: it is how
     * a person reads the list rather than a question they are asking this
     * minute. Unlike a filter it hides nothing permanently — the heading stays,
     * with its count — so restoring it on launch cannot make a busy fleet look
     * quiet.
     *
     * The read and the write are one `update {}` rather than a read of
     * `local.value` followed by a write, so two taps cannot both observe the
     * same value and both write the same answer, losing one. `updated` is
     * assigned inside the lambda and read after: `update` may run its lambda
     * more than once under contention, and the last run is the one that
     * committed, so what is persisted is what the flow holds.
     */
    fun toggleHost(alias: String) {
        var updated: Set<String> = emptySet()
        local.update {
            updated = if (alias in it.collapsedHosts) it.collapsedHosts - alias else it.collapsedHosts + alias
            it.copy(collapsedHosts = updated)
        }
        // Sorted, so the stored value does not churn on a set whose iteration
        // order is not promised — a store that rewrites the same content in a
        // different order is a store that looks like it changed.
        prefs?.putStringList(COLLAPSED_KEY, updated.sorted())
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
        val modes = GroupMode.entries.filter { it != GroupMode.HOST && (it != GroupMode.WORK || workAvailable) }
        val next = modes[(modes.indexOf(local.value.groupMode).coerceAtLeast(0) + 1) % modes.size]
        setGroupMode(next)
    }

    /** Only sessions on *My work* tickets, or all of them again. Never talks to the hub. */
    fun toggleMyWorkOnly() {
        filter { it.copy(myWorkOnly = !it.myWorkOnly) }
    }

    /**
     * Only [org]'s sessions, or all of them again — tapping the chosen org
     * again clears it. Never talks to the hub: it narrows the rows the token
     * already sees (every org's, or one org's for a phone paired with
     * `--org`), so this is a way of reading the list, not a scope.
     */
    fun toggleOrg(org: Long) {
        filter { it.copy(orgFilter = if (it.orgFilter == org) null else org) }
    }

    /** Show only [org]'s rows: an organisation's Sessions row (14.17) opens the list this way. */
    fun showOrg(org: Long) {
        filter { it.copy(orgFilter = org) }
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
        // Only a hub with the work graph has a ticket cache worth overlaying;
        // without one these are the rows as they came.
        val rows = if (work.available) sessions.map { it.withTicketsFrom(work.tickets) } else sessions
        val statusNames = if (work.available) workStatusNames(rows) else emptyList()
        // Like the toggles above: a filter nobody can see is dropped, so a
        // hub that stops listing a second org cannot leave the list narrowed,
        // and neither can a *My work* filter whose tracker went away.
        val filters = l.filters.copy(
            myWorkOnly = l.filters.myWorkOnly && myWorkAvailable,
            orgFilter = l.filters.orgFilter?.takeIf { f -> choices.any { it.id == f } },
            // The ticket filters read the work graph's fields; a hub without
            // it has no control for them on screen, so they are off.
            workStatuses = if (work.available) l.filters.workStatuses else emptySet(),
            // A name no row's work is in any more has no chip to turn it off,
            // so it stops narrowing — the desktop's rule for the same filter.
            workStatusNames = l.filters.workStatusNames.filterTo(mutableSetOf()) { n ->
                statusNames.any { it.equals(n, ignoreCase = true) }
            },
        )
        // Without the work graph nothing is archived and there is no switch
        // for it, so nothing is hidden as archived. The person's choice is
        // kept as it is ([filters] is what the screen shows); only what is
        // matched against widens.
        val applied = if (work.available) filters else filters.copy(showArchived = true)
        val myWork = work.myWork?.takeIf { filters.myWorkOnly }
        val urgency = groupMode == GroupMode.URGENCY
        // The queue is flat, so it is filtered here rather than grouped: the
        // narrowing is the same `matches` the tree uses, with the same project
        // label handed in, so the two views can never keep different rows.
        val byId = projects.associateBy { it.id }
        val urgent = if (!urgency) {
            emptyList()
        } else {
            rows.filter { row ->
                row.matches(applied, l.filterAt, projectLabel(row.projectId, byId)) &&
                    (myWork == null || row.work?.itemId in myWork)
            }.byTriage(now = nowSeconds)
        }
        // Counted against the same `matches` the two views use, so the number
        // can never describe a row that either of them would have drawn. The
        // host filter is included because it narrows like any other — and
        // `onClearAll`, which is what the warning offers, already clears the
        // navigator's copy along with the rest.
        fun kept(row: SessionRow, f: SessionFilters) =
            row.matches(f, l.filterAt, projectLabel(row.projectId, byId)) && (myWork == null || row.work?.itemId in myWork)
        // Hidden only by the archived default: not something *Clear all*
        // brings back, so not what the banner counts — the archived row at
        // the end of the list says these, and how many want a person.
        val archivedOut = if (applied.showArchived) {
            emptyList()
        } else {
            val widened = applied.copy(showArchived = true)
            rows.filter { it.work?.archivedAt != null && kept(it, widened) }
        }
        val archivedIds = archivedOut.mapTo(HashSet()) { it.id }
        val hiddenAttention = rows.count { row ->
            row.needsAttention && !kept(row, applied) && row.id !in archivedIds
        }
        val pinned = if (urgency || filters.needsAttentionOnly) {
            emptyList()
        } else {
            rows.filter { it.triageBucket(now = nowSeconds).needsYou && kept(it, applied) }.byTriage(now = nowSeconds)
        }
        val groups = if (urgency) {
            emptyList()
        } else {
            groupSessions(
                rows,
                hosts,
                projects,
                applied,
                l.filterAt,
                byWork,
                myWork,
                orgLabel = if (choices.isNotEmpty() && filters.orgFilter == null) work.orgs::name else null,
                collapsedHosts = l.collapsedHosts,
                flat = groupMode == GroupMode.HOST,
            )
        }
        return SessionsUiState(
            groups = groups,
            status = status,
            filters = filters,
            attentionCount = sessions.count { it.needsAttention },
            hiddenAttention = hiddenAttention,
            shown = if (urgency) urgent.size else groups.sumOf { it.sessionCount },
            total = sessions.size,
            refreshing = l.refreshing,
            error = l.error,
            nowSeconds = nowSeconds,
            workAvailable = work.available,
            groupMode = groupMode,
            urgent = urgent,
            pinned = pinned,
            // Never while refused: that hub will not connect however long the
            // spinner turns, and the banner above already says why.
            connecting = l.liveAt == null && sessions.isEmpty() && status !is ConnectionStatus.Refused,
            staleFor = if (status is ConnectionStatus.Connected) null else l.liveAt?.let { relativeTime(it, nowSeconds) },
            staleAt = if (status is ConnectionStatus.Connected) null else l.liveAt?.let { clockLabel(it, utcOffset(it)) },
            myWorkAvailable = myWorkAvailable,
            orgChoices = choices,
            accountNames = work.accountNames,
            accountUsage = work.accountUsage,
            orgColors = orgColors(choices),
            hostChoices = hostChoices(sessions, hosts),
            projectChoices = projectChoices(sessions, projects, filters.projectFilter),
            filtersOpen = l.filtersOpen,
            searchOpen = l.searchOpen,
            workStatusNameChoices = statusNames,
            archivedHidden = archivedOut.size,
            archivedAttention = archivedOut.count { it.needsAttention },
        )
    }

    private companion object {
        const val BY_WORK_KEY = "sessions.by_work"
        const val GROUP_MODE_KEY = "sessions.group_mode"
        const val COLLAPSED_KEY = "sessions.collapsed_hosts"
        /** v2: archived sessions hidden by default. */
        const val FILTERS_KEY = "sessions.filters.v2"
        const val FILTERS_KEY_V1 = "sessions.filters"
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

/** The bar colours of [choices], by org id: only the orgs whose colour parses. */
internal fun orgColors(choices: List<OrgInfo>): Map<Long, Long> =
    choices.mapNotNull { o -> orgColorArgb(o.color)?.let { o.id to it } }.toMap()

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
    collapsedHosts: Set<String> = emptySet(),
    /** [GroupMode.HOST]: one unlabelled group per host, most recent first, in place of its projects. */
    flat: Boolean = false,
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
            if (flat) {
                return@map HostGroup(
                    alias = alias,
                    reachable = reachability[alias],
                    collapsed = alias in collapsedHosts,
                    projects = listOf(ProjectGroup(projectId = null, label = "", sessions = rows.sortedWith(BY_RECENCY))),
                )
            }
            val (keyed, rest) = if (byWork) rows.partition { it.workGroupKey != null } else emptyList<SessionRow>() to rows
            HostGroup(
                alias = alias,
                reachable = reachability[alias],
                collapsed = alias in collapsedHosts,
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
