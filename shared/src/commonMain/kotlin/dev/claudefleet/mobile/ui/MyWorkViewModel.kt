package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.Facet
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.IdOrWord
import dev.claudefleet.mobile.model.TreeGroup
import dev.claudefleet.mobile.model.TreeOrg
import dev.claudefleet.mobile.model.TreeTracker
import dev.claudefleet.mobile.model.WorkRule
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkTreePage
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.model.WorkViewDraft
import dev.claudefleet.mobile.model.WorkFacetId
import dev.claudefleet.mobile.model.without
import dev.claudefleet.mobile.model.workFacets
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.utcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One group's section under an org: its header (with the hub's count) and the tasks loaded so far. */
data class WorkGroupSection(
    /** `<org>|<group id>` — what collapsing and *Load more* key on. */
    val key: String,
    val orgId: Long?,
    val group: GroupRef,
    /** Every task of the group under the filters, not only the loaded ones. */
    val count: Int,
    val collapsed: Boolean = false,
    val tasks: List<WorkTask> = emptyList(),
    /** More of this section exists than is loaded: *Load more* is offered. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Friendly? = null,
)

/** One org's section: its groups, and a count over all of them. */
data class WorkOrgSection(
    /** `org:<id>` or `org:none`. */
    val key: String,
    val orgId: Long?,
    val name: String,
    val color: String? = null,
    val count: Int,
    val collapsed: Boolean = false,
    val groups: List<WorkGroupSection> = emptyList(),
)

data class MyWorkUiState(
    /** The hub serves `work { tree }`: the Work tab is drawn at all. */
    val available: Boolean = false,
    val loading: Boolean = false,
    /** A page has answered — "nothing here" is only said after one has. */
    val loaded: Boolean = false,
    val orgs: List<WorkOrgSection> = emptyList(),
    /** Tasks under the filters, across every section. */
    val total: Int = 0,
    val filters: WorkTreeFilters = WorkTreeFilters(),
    val searchOpen: Boolean = false,
    val filtersOpen: Boolean = false,
    /** What the filter sheet can offer, from the last page. */
    val filterOrgs: List<TreeOrg> = emptyList(),
    val filterTrackers: List<TreeTracker> = emptyList(),
    val viewsAvailable: Boolean = false,
    val views: List<WorkView> = emptyList(),
    /** The saved view whose filters are the ones showing, if any. */
    val activeViewId: Long? = null,
    /** Saving a view needs a full token, a hub that lists it, and a connection. */
    val canSaveView: Boolean = false,
    val canDeleteView: Boolean = false,
    val connected: Boolean = false,
    /** "Offline · as of 10:42" over a page kept from before the connection went. */
    val stale: String? = null,
    val error: Friendly? = null,
    /** The error is a write another device beat: *Reload* is offered beside it. */
    val conflict: Boolean = false,
    val reviewAvailable: Boolean = false,
    val reviewCount: Int = 0,
    val rulesAvailable: Boolean = false,
    val rulesOpen: Boolean = false,
    val rules: List<WorkRule> = emptyList(),
    /**
     * Tasks the hub hid as archived under these filters (`archived_hidden`):
     * what the *N archived tasks hidden · Show archived* row at the end of
     * the list counts. 0 from an older hub, which hides none.
     */
    val archivedHidden: Int = 0,
    /** Every filter that narrows the tree, in the desktop's words ([workFacets]). */
    val facets: List<Facet<WorkFacetId>> = emptyList(),
) {
    val isEmpty: Boolean get() = loaded && orgs.isEmpty()

    /** Archived tasks are being shown ([WorkTreeFilters.archived]). */
    val showArchived: Boolean get() = filters.archived == true

    /**
     * The strip under the header: one removable chip per filter the sheet
     * holds. Search and the two toggles show their own state on screen.
     */
    val stripFacets: List<Facet<WorkFacetId>> get() = facets.filterNot { it.id.onScreen }

    /** The archived row is drawn: some are hidden, or they are being shown (and can be hidden again). */
    val archivedRow: Boolean get() = loaded && (archivedHidden > 0 || showArchived)
}

/**
 * The **My work** tab (claude-fleet M14's Work view on the phone): org →
 * group → task, from `work { tree }`.
 *
 * The hub answers every section header with its count (`groups`) and the
 * first page of tasks in section order; a section then loads the rest of
 * itself page by page with `filters.group` and that section's own cursor
 * (*Load more*). The phone never groups by itself: which org and group a task
 * is under is the hub's answer.
 *
 * **Staying current.** While [attach]ed (the tab is showing) it re-reads the
 * page when the hub says something moved — a `work:*` frame
 * ([FleetState.workChanges]), or a session row whose *work* changed
 * ([workSignatureChanges]: its links, guess, org or `work_rev`, never its
 * status churn) — at once and then at most once per [refreshDebounceMs]
 * ([throttleLatest], so a busy fleet cannot starve it), and whenever the
 * connection comes (back) up. The two kinds of change are throttled apart,
 * so a `work:changed` is never held behind a stream of session rows. While
 * not connected it keeps the last page and says how old it is; a write
 * then is refused with a message, and nothing queues.
 *
 * **The tab's badge** ([MyWorkUiState.reviewCount]) follows the hub even
 * while the tab is not showing: re-read on every (re)connect — which is also
 * what coming back to the foreground is — and on work changes.
 *
 * **Refresh and Load more.** Every re-read that replaces the list bumps a
 * generation; a *Load more* answer from before it is dropped rather than
 * appended to a list it was not read against.
 *
 * **Remembered.** The last filters and which sections are folded, in [prefs].
 * Saved views are the hub's (`work { views }`), shared with the desktop.
 */
class MyWorkViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val prefs: Prefs? = null,
    /** This device's clock, unix seconds — the fallback "as of" when a page has no `generated_at`. */
    private val clock: () -> Long = { epochSeconds() },
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
    private val refreshDebounceMs: Long = 2_000,
    private val pageSize: Int = PAGE,
) {
    private data class SectionData(
        val tasks: List<WorkTask> = emptyList(),
        /** The section's own cursor; null until a *Load more* has read it once. */
        val cursor: String? = null,
        /** The section's own read said there is nothing after [cursor]. */
        val exhausted: Boolean = false,
        /** *Load more* was used: a re-read refreshes this section on its own. */
        val extended: Boolean = false,
        val loadingMore: Boolean = false,
        val error: Friendly? = null,
    )

    private data class Local(
        val page: WorkTreePage? = null,
        val sections: Map<String, SectionData> = emptyMap(),
        /** When [page] was read, in unix seconds. */
        val asOf: Long? = null,
        val filters: WorkTreeFilters = WorkTreeFilters(),
        val loading: Boolean = false,
        val error: Friendly? = null,
        val conflict: Boolean = false,
        val searchOpen: Boolean = false,
        val filtersOpen: Boolean = false,
        val views: List<WorkView> = emptyList(),
        val reviewCount: Int = 0,
        val rulesOpen: Boolean = false,
        val rules: List<WorkRule> = emptyList(),
        val collapsed: Set<String> = emptySet(),
        /** Bumped by every re-read that replaces the list; what a *Load more* answer is checked against. */
        val generation: Long = 0,
    )

    private val local = MutableStateFlow(
        Local(
            filters = readFilters(),
            collapsed = prefs?.getStringList(COLLAPSED_KEY).orEmpty().toSet(),
        ),
    )

    val state: StateFlow<MyWorkUiState> =
        combine(fleet.capabilities, fleet.status, local) { caps, status, l -> assemble(caps, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, local.value))

    private var follow: Job? = null
    private var loadJob: Job? = null
    private var queryJob: Job? = null

    init {
        // The badge, while the tab is not showing (showing, [load] reads it
        // with the tree): on every (re)connect — a resume from the
        // background is one — and on every work change.
        scope.launch {
            combine(fleet.capabilities, fleet.status) { caps, status -> caps.lists(WORK, TREE) && caps.has(WORK, REVIEW) && status.isConnected() }
                .distinctUntilChanged()
                .collect { ready -> if (ready && !attached) refreshReviewCount() }
        }
        scope.launch {
            merge(fleet.workChanges.throttleLatest(refreshDebounceMs), fleet.workSignatureChanges().throttleLatest(refreshDebounceMs))
                .collect { if (!attached && fleet.status.value.isConnected()) refreshReviewCount() }
        }
    }

    private val attached: Boolean get() = follow?.isActive == true

    /**
     * The tab is showing: read now if the hub is up, and keep re-reading when
     * the fleet moves. Idempotent.
     */
    fun attach() {
        if (follow?.isActive == true) return
        follow = scope.launch {
            launch {
                // Reads when the view becomes readable — the hub listing `tree`
                // (discovery lands a beat after `ready`) on a live connection
                // — and again on every reconnect.
                combine(fleet.capabilities, fleet.status) { caps, status -> caps.lists(WORK, TREE) && status.isConnected() }
                    .distinctUntilChanged()
                    .collect { ready -> if (ready) reload() }
            }
            // Throttled apart: a `work:changed` is never held behind a
            // stream of session rows, and a row whose status merely moved
            // is not a change at all ([workSignatureChanges]).
            merge(fleet.workChanges.throttleLatest(refreshDebounceMs), fleet.workSignatureChanges().throttleLatest(refreshDebounceMs))
                .collect { if (fleet.status.value.isConnected()) reload() }
        }
    }

    /** The tab is gone: stop following. The page stays for the next visit. */
    fun detach() {
        follow?.cancel()
        follow = null
    }

    /** Re-read the tree from the top (and every section *Load more* extended), the views and the review count. */
    fun reload(): Job {
        loadJob?.cancel()
        return scope.launch { load() }.also { loadJob = it }
    }

    /** A pull on the list, or *Reload* after a conflict. */
    fun refresh(): Job = reload()

    /** Re-read only the review inbox's total — the Work tab's badge. */
    fun refreshReviewCount(): Job = scope.launch {
        val caps = fleet.capabilities.value
        if (!caps.lists(WORK, TREE) || !caps.has(WORK, REVIEW)) return@launch
        val total = readOrNull(REVIEW) { actions.review(limit = 1).total } ?: return@launch
        local.update { it.copy(reviewCount = total) }
    }

    private suspend fun load() {
        val caps = fleet.capabilities.value
        if (!caps.lists(WORK, TREE)) return
        val filters = local.value.filters
        local.update { it.copy(loading = true) }
        try {
            val extended = local.value.sections.filterValues { it.extended }
            val (page, views, reviewCount) = coroutineScope {
                val page = async { actions.tree(filters, limit = pageSize, perTask = PER_TASK) }
                val views = async { if (caps.has(WORK, VIEWS)) readOrNull(VIEWS) { actions.views() } else null }
                val review = async { if (caps.has(WORK, REVIEW)) readOrNull(REVIEW) { actions.review(limit = 1).total } else null }
                Triple(page.await(), views.await(), review.await())
            }
            // A section someone paged through is re-read on its own, as far
            // as they had got, so a refresh does not fold it back to the
            // first page's share of it.
            val refreshed = extended.mapNotNull { (key, data) ->
                val group = page.groups.firstOrNull { sectionKey(it.orgId, it.group.id) == key } ?: return@mapNotNull null
                val limit = data.tasks.size.coerceIn(pageSize, MAX_LIMIT)
                val answer = actions.tree(sectionFilters(filters, group), limit = limit, perTask = PER_TASK)
                key to SectionData(
                    tasks = answer.tasks,
                    cursor = answer.nextCursor,
                    exhausted = answer.nextCursor == null,
                    extended = true,
                )
            }.toMap()
            local.update {
                it.copy(
                    page = page,
                    sections = distribute(page) + refreshed,
                    asOf = page.generatedAt ?: clock(),
                    loading = false,
                    error = null,
                    conflict = false,
                    views = views ?: it.views,
                    reviewCount = reviewCount ?: it.reviewCount,
                    generation = it.generation + 1,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, TREE)
            local.update { it.copy(loading = false, error = friendlyWork(t), conflict = false) }
        }
    }

    /** The first page's tasks, cut into their sections. */
    private fun distribute(page: WorkTreePage): Map<String, SectionData> =
        page.tasks.groupBy { sectionKey(it.orgId, it.group.id) }.mapValues { (_, tasks) -> SectionData(tasks = tasks) }

    /** *Load more* for one section: its next page, with `filters.group` and its own cursor. */
    fun loadMore(key: String): Job? {
        val l = local.value
        val group = l.page?.groups?.firstOrNull { sectionKey(it.orgId, it.group.id) == key } ?: return null
        val data = l.sections[key] ?: SectionData()
        if (data.loadingMore || data.exhausted || !fleet.status.value.isConnected()) return null
        local.update { it.copy(sections = it.sections + (key to data.copy(loadingMore = true, error = null))) }
        val filters = l.filters
        val generation = l.generation
        return scope.launch {
            try {
                val answer = actions.tree(sectionFilters(filters, group), cursor = data.cursor, limit = pageSize, perTask = PER_TASK)
                local.update { now ->
                    // The filters changed while this was out, or a refresh
                    // replaced the list: its answer is for a list no longer
                    // showing, and appending it would repeat or misplace tasks.
                    if (now.filters != filters || now.generation != generation) return@update now
                    val current = now.sections[key] ?: SectionData()
                    val merged = (current.tasks + answer.tasks).distinctBy { it.taskId }
                    now.copy(
                        sections = now.sections + (
                            key to SectionData(
                                tasks = merged,
                                cursor = answer.nextCursor,
                                exhausted = answer.nextCursor == null,
                                extended = true,
                            )
                            ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { now ->
                    if (now.generation != generation) return@update now
                    val current = now.sections[key] ?: SectionData()
                    now.copy(sections = now.sections + (key to current.copy(loadingMore = false, error = friendlyWork(t))))
                }
            }
        }
    }

    /** Fold a section (an org's or a group's) away, or open it. Remembered. */
    fun toggleSection(key: String) {
        local.update { l -> l.copy(collapsed = if (key in l.collapsed) l.collapsed - key else l.collapsed + key) }
        prefs?.putStringList(COLLAPSED_KEY, local.value.collapsed.sorted())
    }

    // ---- filters and views ----

    /** Replace the filters, remember them, and re-read. */
    fun setFilters(filters: WorkTreeFilters) {
        val next = filters.normalized().copy(group = null)
        if (next == local.value.filters) return
        remember(next)
        local.update { it.copy(filters = next, sections = it.sections.forgettingCursors()) }
        if (fleet.status.value.isConnected()) reload()
    }

    /**
     * What is loaded stays on screen until the re-read replaces it, but no
     * section's cursor survives a change of filters: the hub refuses a cursor
     * used with other filters.
     */
    private fun Map<String, SectionData>.forgettingCursors(): Map<String, SectionData> =
        mapValues { (_, d) -> d.copy(cursor = null, exhausted = false, extended = false, loadingMore = false) }

    fun setOrg(org: IdOrWord?) = setFilters(local.value.filters.copy(org = org))
    fun setTracker(tracker: IdOrWord?) = setFilters(local.value.filters.copy(tracker = tracker))
    fun setStatus(status: String?) = setFilters(local.value.filters.copy(status = status))
    fun setHas(has: String?) = setFilters(local.value.filters.copy(has = has))
    fun toggleMine() = setFilters(local.value.filters.copy(mine = local.value.filters.mine != true))
    fun toggleReview() = setFilters(local.value.filters.copy(review = local.value.filters.review != true))

    /** Show archived tasks too, or hide them again (the default). Not a narrowing: it is not counted. */
    fun setArchived(on: Boolean) = setFilters(local.value.filters.copy(archived = on))
    fun toggleArchived() = setArchived(local.value.filters.archived != true)

    /** One chip's ✕: that filter back to *Any*, the rest kept. */
    fun clearFacet(id: WorkFacetId) {
        if (id == WorkFacetId.QUERY) queryJob?.cancel()
        setFilters(local.value.filters.without(id))
    }

    /**
     * Every filter off — the search included, and archived tasks hidden
     * again: the desktop's *Clear all* / *Clear filters*. It used to keep the
     * search, so on a tree emptied by a search alone the empty state's
     * *Clear filters* changed nothing at all.
     */
    fun clearFilters() {
        queryJob?.cancel()
        setFilters(WorkTreeFilters())
    }

    /** The search box: typed text narrows the tree after a short pause. */
    fun setQuery(text: String) {
        val next = local.value.filters.copy(query = text).normalized()
        remember(next)
        local.update { it.copy(filters = next, sections = it.sections.forgettingCursors()) }
        queryJob?.cancel()
        queryJob = scope.launch {
            delay(QUERY_DEBOUNCE_MS)
            if (fleet.status.value.isConnected()) reload()
        }
    }

    fun toggleSearch() {
        val open = !local.value.searchOpen
        local.update { it.copy(searchOpen = open) }
        if (!open && local.value.filters.query != null) setQuery("")
    }

    fun setFiltersOpen(open: Boolean) {
        local.update { it.copy(filtersOpen = open) }
    }

    /** Show a saved view: its filters become the ones in use (and remembered). */
    fun applyView(view: WorkView) {
        setFilters(view.filters)
    }

    /** **Save as view…**: the filters showing, under [name]. A new view expects none (`expected_version` 0). */
    fun saveView(name: String): Job? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        if (!allowed(VIEW_SAVE)) return null.also { refuseOffline(VIEW_SAVE) }
        return write(VIEW_SAVE) {
            actions.saveView(WorkViewDraft(name = trimmed, filters = local.value.filters, expectedVersion = 0))
            reloadViews()
        }
    }

    /** **Update view**: the active view takes the filters showing, under its version. */
    fun updateView(view: WorkView): Job? {
        if (!allowed(VIEW_SAVE)) return null.also { refuseOffline(VIEW_SAVE) }
        return write(VIEW_SAVE) {
            actions.saveView(WorkViewDraft(id = view.id, name = view.name, filters = local.value.filters, expectedVersion = view.version))
            reloadViews()
        }
    }

    fun deleteView(view: WorkView): Job? {
        if (!allowed(VIEW_DELETE)) return null.also { refuseOffline(VIEW_DELETE) }
        return write(VIEW_DELETE) {
            actions.deleteView(view.id)
            reloadViews()
        }
    }

    private suspend fun reloadViews() {
        val views = readOrNull(VIEWS) { actions.views() } ?: return
        local.update { it.copy(views = views) }
    }

    // ---- rules: read-only on the phone ----

    fun openRules(): Job? {
        if (!fleet.capabilities.value.has(WORK, RULES)) return null
        local.update { it.copy(rulesOpen = true) }
        return scope.launch {
            try {
                val rules = actions.rules()
                local.update { it.copy(rules = rules) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, RULES)
                local.update { it.copy(error = friendlyWork(t)) }
            }
        }
    }

    fun closeRules() {
        local.update { it.copy(rulesOpen = false) }
    }

    fun dismissError() {
        local.update { it.copy(error = null, conflict = false) }
    }

    /**
     * The groups of the last page a placement may name — what *Place in
     * group…* lists: `label:` groups a person or a rule made, never a
     * tracker's container, a repository or a key ([placeableGroups]).
     */
    fun knownGroups(): List<GroupRef> =
        local.value.page?.groups.orEmpty().map { it.group }.filter { it.isPlaceable }.distinctBy { it.title }

    /**
     * One write, never shown as saved before the hub answers. Gated again
     * here on the live sources, so a tap in the frame before a button goes
     * cannot reach a tool the token may not call.
     */
    private fun write(action: String, call: suspend () -> Unit): Job = scope.launch {
        if (!allowed(action)) {
            refuseOffline(action)
            return@launch
        }
        try {
            call()
            local.update { it.copy(error = null, conflict = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, action)
            local.update { it.copy(error = friendlyWorkWrite(t), conflict = t.isConflict()) }
        }
    }

    private fun allowed(action: String): Boolean =
        canWrite && fleet.status.value.isConnected() && fleet.capabilities.value.has(WORK_LINK, action)

    /** A write this token and hub may make, tapped while offline: said, never silently dropped. */
    private fun refuseOffline(action: String) {
        if (canWrite && fleet.capabilities.value.has(WORK_LINK, action) && !fleet.status.value.isConnected()) {
            local.update { it.copy(error = OFFLINE_WRITE, conflict = false) }
        }
    }

    private suspend fun <T> readOrNull(action: String, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, action)
        null
    }

    private fun readFilters(): WorkTreeFilters {
        val stored = prefs?.getStringList(FILTERS_KEY)?.firstOrNull() ?: return WorkTreeFilters()
        return try {
            json.decodeFromString(WorkTreeFilters.serializer(), stored).normalized().copy(group = null)
        } catch (_: Exception) {
            WorkTreeFilters()
        }
    }

    private fun remember(filters: WorkTreeFilters) {
        prefs?.putStringList(FILTERS_KEY, listOf(json.encodeToString(WorkTreeFilters.serializer(), filters.copy(group = null))))
    }

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, l: Local): MyWorkUiState {
        val available = caps.lists(WORK, TREE)
        if (!available) return MyWorkUiState()
        val connected = status.isConnected()
        val page = l.page
        val orgs = page?.let { sections(it, l) }.orEmpty()
        return MyWorkUiState(
            available = true,
            loading = l.loading,
            loaded = page != null,
            orgs = orgs,
            total = page?.total ?: 0,
            filters = l.filters,
            searchOpen = l.searchOpen || l.filters.query != null,
            filtersOpen = l.filtersOpen,
            filterOrgs = page?.orgs.orEmpty(),
            filterTrackers = page?.trackers.orEmpty(),
            viewsAvailable = caps.has(WORK, VIEWS),
            views = l.views,
            activeViewId = l.views.firstOrNull { it.filters.normalized().copy(group = null) == l.filters }?.id,
            canSaveView = canWrite && connected && caps.has(WORK_LINK, VIEW_SAVE),
            canDeleteView = canWrite && connected && caps.has(WORK_LINK, VIEW_DELETE),
            connected = connected,
            stale = if (!connected && page != null) l.asOf?.let { staleLine(it, utcOffset(it)) } else null,
            error = l.error,
            conflict = l.conflict,
            reviewAvailable = caps.has(WORK, REVIEW),
            reviewCount = l.reviewCount,
            rulesAvailable = caps.has(WORK, RULES),
            rulesOpen = l.rulesOpen,
            rules = l.rules,
            archivedHidden = page?.archivedHidden ?: 0,
            facets = workFacets(
                l.filters,
                orgName = { id -> page?.orgs?.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } },
                trackerName = { id -> page?.trackers?.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } },
            ),
        )
    }

    /** Org → group sections from the page's headers, in the hub's order, with what is loaded of each. */
    private fun sections(page: WorkTreePage, l: Local): List<WorkOrgSection> {
        val headers: List<TreeGroup> = page.groups.ifEmpty {
            // A hub that sent tasks and no headers still gets sections: one per task group seen.
            page.tasks.distinctBy { sectionKey(it.orgId, it.group.id) }.map { t ->
                TreeGroup(t.orgId, null, t.group, page.tasks.count { sectionKey(it.orgId, it.group.id) == sectionKey(t.orgId, t.group.id) })
            }
        }
        val orgNames = page.orgs.associateBy { it.id }
        return headers.groupBy { it.orgId }.map { (orgId, groups) ->
            val orgKey = orgKey(orgId)
            WorkOrgSection(
                key = orgKey,
                orgId = orgId,
                name = orgId?.let { id -> groups.firstNotNullOfOrNull { it.orgName } ?: orgNames[id]?.name?.takeIf { it.isNotBlank() } ?: "Org $id" }
                    ?: "Unassigned",
                color = orgId?.let { orgNames[it]?.color },
                count = groups.sumOf { it.count },
                collapsed = orgKey in l.collapsed,
                groups = groups.map { g ->
                    val key = sectionKey(g.orgId, g.group.id)
                    val data = l.sections[key] ?: SectionData()
                    WorkGroupSection(
                        key = key,
                        orgId = g.orgId,
                        group = g.group,
                        count = g.count,
                        collapsed = key in l.collapsed,
                        tasks = data.tasks,
                        hasMore = !data.exhausted && data.tasks.size < g.count,
                        loadingMore = data.loadingMore,
                        error = data.error,
                    )
                },
            )
        }
    }

    companion object {
        const val TREE = "tree"
        const val VIEWS = "views"
        const val REVIEW = "review"
        const val RULES = "rules"
        const val VIEW_SAVE = "view_save"
        const val VIEW_DELETE = "view_delete"

        /** Tasks per page — the hub's default. */
        const val PAGE = 50

        /** The most one read may ask for. */
        const val MAX_LIMIT = 200

        /** Sessions per task in the tree: the phone's cards show counts, not occurrences. */
        const val PER_TASK = 0

        const val QUERY_DEBOUNCE_MS = 400L
        const val FILTERS_KEY = "work.filters"
        const val COLLAPSED_KEY = "work.collapsed"

        fun orgKey(orgId: Long?): String = "org:${orgId ?: "none"}"

        fun sectionKey(orgId: Long?, groupId: String): String = "${orgId ?: "none"}|$groupId"

        /** The filters one section's page is read with: the group, pinned to its org. */
        internal fun sectionFilters(base: WorkTreeFilters, group: TreeGroup): WorkTreeFilters =
            base.copy(group = group.group.id, org = group.orgId?.let(IdOrWord::of) ?: IdOrWord.NONE)
    }
}
