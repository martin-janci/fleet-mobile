package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkViewActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.OrgBrief
import dev.claudefleet.mobile.model.OrgChoice
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TaskHasChoice
import dev.claudefleet.mobile.model.TaskStatusChoice
import dev.claudefleet.mobile.model.TrackerBrief
import dev.claudefleet.mobile.model.TrackerChoice
import dev.claudefleet.mobile.model.TreePage
import dev.claudefleet.mobile.model.WorkFilters
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_VIEWS
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.utcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One section of the tree: an org's group. The org is part of the key — `none` exists in every org. */
data class WorkSectionKey(val orgId: Long?, val groupId: String)

data class WorkSection(
    val key: WorkSectionKey,
    val group: GroupRef,
    /** Every task in the section under the filters — the hub's count, not what is loaded. */
    val count: Int,
    val tasks: List<WorkTask>,
    val collapsed: Boolean = false,
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
)

data class WorkOrgSection(
    val orgId: Long?,
    val name: String,
    val count: Int,
    val sections: List<WorkSection>,
    val collapsed: Boolean = false,
)

data class WorkTreeUiState(
    /** The hub's schema lists `work { tree }`: the tab exists. Never true on an older hub. */
    val available: Boolean = false,
    /** A first page has answered since the tab was opened with these filters. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** A pull-to-refresh is underway. */
    val refreshing: Boolean = false,
    val orgs: List<WorkOrgSection> = emptyList(),
    val total: Int = 0,
    val filters: WorkFilters = WorkFilters(),
    /** Saved views as chips, applied read-only. Empty on a hub that does not list `views`. */
    val views: List<WorkView> = emptyList(),
    /** The chip whose filters are the ones applied, if any. */
    val activeViewId: Long? = null,
    val filtersOpen: Boolean = false,
    /** The filter sheet's org choices: what the hub said this token sees — nothing assumed. */
    val orgChoices: List<OrgBrief> = emptyList(),
    /** *Unassigned* is offered only once the hub has shown unassigned work to this token. */
    val offerUnassigned: Boolean = false,
    val trackerChoices: List<TrackerBrief> = emptyList(),
    /** "Offline · as of 10:42" while the stream is down; null while connected. */
    val stale: String? = null,
    val error: Friendly? = null,
) {
    val isEmpty: Boolean get() = loaded && orgs.isEmpty()
}

/**
 * The *My work* tab (claude-fleet M14.4, read part): org → group → task,
 * every task with its sessions, from `work { tree }`.
 *
 * **Gate.** The tab exists only when the hub's `tools/list` names `tree` in
 * `work`'s action enum ([HubCapabilities.workView]); on an older hub it is
 * hidden, never shown and refused.
 *
 * **Paging.** The first page is one `tree` call over every section; the hub
 * answers every section's header and count with it, in its own order (orgs
 * by name, unassigned last; groups by label, `none` last), so the page fills
 * the first sections and the rest show their count and *Load more*. A
 * section's *Load more* asks for that section alone (`filters.org` +
 * `filters.group`), from the top the first time — a cursor is bound to the
 * filters it was answered for, so the main page's cannot be reused — and by
 * its own cursor after that.
 *
 * **Refresh.** No `work:changed` reaches a phone bound to an org (the hub
 * keeps `work` frames from bound clients, claude-fleet #347), so this never
 * waits for one: it re-reads when the tab gains focus, on a pull, when the
 * stream comes back, and — debounced — when a `session:*` change touches a
 * session the tab shows or one that carries work. No offline queue: there
 * is nothing to queue, and a read that fails while offline keeps the last
 * picture under the stale banner.
 */
class WorkTreeViewModel(
    private val fleet: FleetState,
    private val actions: WorkViewActions,
    private val scope: CoroutineScope,
    /** This device's clock, unix seconds: when a picture was read, for the stale banner. */
    private val clock: () -> Long = { epochSeconds() },
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
    /** How long a burst of session changes is gathered before one re-read. */
    private val debounceMs: Long = SESSION_DEBOUNCE_MS,
) {
    /** One section's own pages, once *Load more* has asked for it. */
    private data class SectionPage(val tasks: List<WorkTask>, val cursor: String?)

    private data class Local(
        val page: TreePage? = null,
        val sectionPages: Map<WorkSectionKey, SectionPage> = emptyMap(),
        val loadingMore: Set<WorkSectionKey> = emptySet(),
        val collapsedSections: Set<WorkSectionKey> = emptySet(),
        val collapsedOrgs: Set<Long?> = emptySet(),
        val filters: WorkFilters = WorkFilters(),
        val views: List<WorkView> = emptyList(),
        val filtersOpen: Boolean = false,
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val loadedAt: Long? = null,
        val sawUnassigned: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    /** Bumped by every new read of the tree, so an answer to an older one is dropped. */
    private var generation = 0
    private var visible = false
    private var pendingEvent: Job? = null
    private var pendingQuery: Job? = null

    val state: StateFlow<WorkTreeUiState> =
        combine(fleet.capabilities, fleet.status, local) { caps, status, l -> assemble(caps, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, local.value))

    init {
        scope.launch {
            fleet.sessionChanges.collect { id -> if (visible && relevant(id)) scheduleEventRefresh() }
        }
        // The stream coming back is a focus of its own: whatever changed while
        // it was down was never streamed, and the stale banner should go.
        scope.launch {
            var wasConnected = fleet.status.value is ConnectionStatus.Connected
            fleet.status.collect { status ->
                val connected = status is ConnectionStatus.Connected
                if (connected && !wasConnected && visible) refresh()
                wasConnected = connected
            }
        }
        // A hub that answered `tools/list` after the tab was opened.
        scope.launch {
            fleet.capabilities.collect { caps ->
                if (caps.workView && visible && local.value.page == null && !local.value.loading) refresh()
            }
        }
    }

    /** The tab is on screen: read it again. */
    fun onFocus(): Job? {
        visible = true
        return refresh()
    }

    /** The tab left the screen: session changes stop costing a read. */
    fun onBlur() {
        visible = false
        pendingEvent?.cancel()
    }

    /** Pull-to-refresh. */
    fun pull(): Job? = refresh(pulled = true)

    /**
     * Re-read the first page, the saved views, and every section a *Load
     * more* had grown — each to as many tasks as it showed, so a refresh
     * does not fold a section someone was reading.
     */
    fun refresh(pulled: Boolean = false): Job? {
        val caps = fleet.capabilities.value
        if (!caps.workView) return null
        val gen = ++generation
        val filters = local.value.filters
        val grown = local.value.sectionPages.mapValues { (_, p) -> p.tasks.size }
        local.update { it.copy(loading = it.page == null, refreshing = pulled, error = null) }
        return scope.launch {
            try {
                val (page, sections, views) = coroutineScope {
                    val first = async { actions.tree(filters, limit = PAGE, perTask = PER_TASK) }
                    val again = grown.map { (key, shown) ->
                        async {
                            key to actions.tree(
                                sectionFilters(filters, key),
                                limit = shown.coerceIn(1, MAX_LIMIT),
                                perTask = PER_TASK,
                            )
                        }
                    }
                    val views = if (caps.lists(WORK, WORK_VIEWS)) async { readViews() } else null
                    Triple(first.await(), again.awaitAll(), views?.await())
                }
                if (gen != generation) return@launch
                val loadedAt = clock()
                local.update { l ->
                    l.copy(
                        page = page,
                        sectionPages = sections.associate { (key, p) -> key to SectionPage(p.tasks, p.nextCursor) },
                        loadingMore = emptySet(),
                        views = views ?: l.views,
                        loading = false,
                        refreshing = false,
                        loadedAt = loadedAt,
                        sawUnassigned = l.sawUnassigned || page.groups.any { it.orgId == null },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (gen != generation) return@launch
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, HubCapabilities.WORK_TREE)
                // Offline, the banner already says the picture is old; a
                // second banner saying the read failed says nothing new.
                val offline = fleet.status.value !is ConnectionStatus.Connected && t !is HubError.Tool
                local.update {
                    it.copy(loading = false, refreshing = false, error = if (offline) null else friendlyWork(t))
                }
            }
        }
    }

    /** Views are a nicety: a failure leaves the chips as they were rather than failing the tree. */
    private suspend fun readViews(): List<WorkView>? = try {
        actions.views()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, WORK_VIEWS)
        null
    }

    /** *Load more* under one section. */
    fun loadMore(key: WorkSectionKey): Job? {
        val section = state.value.orgs.flatMap { it.sections }.firstOrNull { it.key == key } ?: return null
        if (!section.canLoadMore || section.loadingMore) return null
        val gen = generation
        val filters = sectionFilters(local.value.filters, key)
        val page = local.value.sectionPages[key]
        local.update { it.copy(loadingMore = it.loadingMore + key, error = null) }
        return scope.launch {
            try {
                val answer = if (page == null) {
                    // From the top: the main page's cursor belongs to other filters.
                    actions.tree(filters, limit = (section.tasks.size + PAGE).coerceAtMost(MAX_LIMIT), perTask = PER_TASK)
                } else {
                    actions.tree(filters, cursor = page.cursor, limit = PAGE, perTask = PER_TASK)
                }
                if (gen != generation) {
                    local.update { it.copy(loadingMore = it.loadingMore - key) }
                    return@launch
                }
                local.update { l ->
                    val before = l.sectionPages[key]?.tasks.orEmpty()
                    val seen = before.mapTo(HashSet()) { it.taskId }
                    val merged = before + answer.tasks.filter { seen.add(it.taskId) }
                    l.copy(
                        sectionPages = l.sectionPages + (key to SectionPage(merged, answer.nextCursor)),
                        loadingMore = l.loadingMore - key,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update {
                    it.copy(loadingMore = it.loadingMore - key, error = if (gen == generation) friendlyWork(t) else it.error)
                }
            }
        }
    }

    fun toggleSection(key: WorkSectionKey) {
        local.update {
            val c = it.collapsedSections
            it.copy(collapsedSections = if (key in c) c - key else c + key)
        }
    }

    fun toggleOrg(orgId: Long?) {
        local.update {
            val c = it.collapsedOrgs
            it.copy(collapsedOrgs = if (orgId in c) c - orgId else c + orgId)
        }
    }

    fun setFiltersOpen(open: Boolean) {
        local.update { it.copy(filtersOpen = open) }
    }

    /**
     * A saved view's chip: its filters, applied as they are. Tapping the
     * chip that is already applied clears them. Nothing is saved: the
     * view's own edits are the second phone PR.
     */
    fun applyView(view: WorkView): Job? {
        val filters = view.parsed ?: return null
        return setFilters(if (state.value.activeViewId == view.id) WorkFilters() else filters)
    }

    fun setOrg(org: OrgChoice?) = setFilters(local.value.filters.copy(org = org))
    fun setTracker(tracker: TrackerChoice?) = setFilters(local.value.filters.copy(tracker = tracker))
    fun setStatus(status: TaskStatusChoice) = setFilters(local.value.filters.copy(status = status))
    fun setHas(has: TaskHasChoice) = setFilters(local.value.filters.copy(has = has))
    fun toggleMine() = setFilters(local.value.filters.copy(mine = !local.value.filters.mine))
    fun toggleReview() = setFilters(local.value.filters.copy(review = !local.value.filters.review))
    fun clearFilters() = setFilters(WorkFilters())

    /** The search box: applied once typing pauses, so each key is not a read. */
    fun setQuery(text: String) {
        local.update { it.copy(filters = it.filters.copy(query = text.take(WorkFilters.QUERY_MAX))) }
        pendingQuery?.cancel()
        pendingQuery = scope.launch {
            delay(QUERY_DEBOUNCE_MS)
            resetAndLoad()
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun setFilters(filters: WorkFilters): Job? {
        pendingQuery?.cancel()
        if (filters == local.value.filters && local.value.page != null) return null
        local.update { it.copy(filters = filters) }
        return resetAndLoad()
    }

    /** New filters: every section's pages belonged to the old ones. */
    private fun resetAndLoad(): Job? {
        local.update { it.copy(page = null, sectionPages = emptyMap(), loadingMore = emptySet()) }
        return refresh()
    }

    private fun scheduleEventRefresh() {
        pendingEvent?.cancel()
        pendingEvent = scope.launch {
            delay(debounceMs)
            refresh()
        }
    }

    /**
     * A change worth a re-read: a resync, a session the tab shows, or a
     * session whose row carries work (it may have just been linked). A
     * session with no work that the tab does not show changes nothing here.
     */
    private fun relevant(id: Long): Boolean {
        if (id == ALL_SESSIONS_CHANGED) return true
        val l = local.value
        val shown = (l.page?.tasks.orEmpty() + l.sectionPages.values.flatMap { it.tasks })
            .any { t -> t.sessions.any { it.sessionId == id } }
        if (shown) return true
        val row: SessionRow = fleet.sessions.value.firstOrNull { it.id == id } ?: return false
        return row.work != null || row.workSuggested != null
    }

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, l: Local): WorkTreeUiState {
        if (!caps.workView) return WorkTreeUiState()
        val page = l.page
        val orgNames = page?.orgs.orEmpty().associate { it.id to it.name }
        val byKey = page?.tasks.orEmpty().groupBy { WorkSectionKey(it.orgId, it.group.id) }
        val headers = page?.groups.orEmpty().map { WorkSectionKey(it.orgId, it.group.id) to it }
        // A task under a header the page did not send cannot happen on a
        // well-behaved hub; drawn under a header of its own rather than lost.
        val orphans = byKey.keys.filter { k -> headers.none { it.first == k } }
        val sections = headers.map { (key, h) -> Triple(key, h.group, h.count) to h.orgName } +
            orphans.map { key -> Triple(key, byKey.getValue(key).first().group, byKey.getValue(key).size) to null }

        val orgs = mutableListOf<WorkOrgSection>()
        for ((triple, orgName) in sections) {
            val (key, group, count) = triple
            val own = l.sectionPages[key]
            val tasks = own?.tasks ?: byKey[key].orEmpty()
            val section = WorkSection(
                key = key,
                group = group,
                count = count,
                tasks = tasks,
                collapsed = key in l.collapsedSections,
                canLoadMore = if (own != null) own.cursor != null else tasks.size < count,
                loadingMore = key in l.loadingMore,
            )
            val last = orgs.lastOrNull()
            if (last != null && last.orgId == key.orgId) {
                orgs[orgs.lastIndex] = last.copy(count = last.count + count, sections = last.sections + section)
            } else {
                val name = orgName ?: key.orgId?.let { orgNames[it] ?: "Organisation $it" } ?: UNASSIGNED
                orgs += WorkOrgSection(key.orgId, name, count, listOf(section), collapsed = key.orgId in l.collapsedOrgs)
            }
        }

        val connected = status is ConnectionStatus.Connected
        return WorkTreeUiState(
            available = true,
            loaded = page != null,
            loading = l.loading,
            refreshing = l.refreshing,
            orgs = orgs,
            total = page?.total ?: 0,
            filters = l.filters,
            views = l.views.filter { it.parsed != null },
            activeViewId = l.views.firstOrNull { it.parsed == l.filters }?.id,
            filtersOpen = l.filtersOpen,
            orgChoices = page?.orgs.orEmpty(),
            offerUnassigned = l.sawUnassigned || l.filters.org == OrgChoice.Unassigned,
            trackerChoices = page?.trackers.orEmpty(),
            stale = if (connected) null else staleSentence(l.loadedAt),
            error = l.error,
        )
    }

    private fun staleSentence(loadedAt: Long?): String =
        if (loadedAt == null) "Offline" else "Offline · as of ${clockTime(loadedAt, utcOffset(loadedAt))}"

    companion object {
        /** Tasks in a page. */
        const val PAGE = 50

        /** The hub's `limit` ceiling. */
        const val MAX_LIMIT = 200

        /** Sessions under each card: a card is compact; the task screen lists them all. */
        const val PER_TASK = 3

        const val SESSION_DEBOUNCE_MS = 1_500L
        const val QUERY_DEBOUNCE_MS = 400L
        const val UNASSIGNED = "Unassigned"

        /** One section's own filters: the tree's, narrowed to its org and group. */
        fun sectionFilters(filters: WorkFilters, key: WorkSectionKey): WorkFilters =
            filters.copy(org = key.orgId?.let { OrgChoice.Org(it) } ?: OrgChoice.Unassigned, group = key.groupId)
    }
}

/** `10:42` — [epochSeconds] on a clock [utcOffsetSeconds] ahead of UTC. Pure, so it is tested without a zone. */
fun clockTime(epochSeconds: Long, utcOffsetSeconds: Int): String {
    val minutes = (epochSeconds + utcOffsetSeconds).mod(86_400L) / 60
    val h = minutes / 60
    val m = minutes % 60
    return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
}
