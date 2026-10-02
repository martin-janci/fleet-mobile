package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.Facet
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgInfo
import dev.claudefleet.mobile.model.ResumeCandidate
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.TicketFacetId
import dev.claudefleet.mobile.model.TicketFilters
import dev.claudefleet.mobile.model.TicketList
import dev.claudefleet.mobile.model.TicketSessionFilter
import dev.claudefleet.mobile.model.TicketSort
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WorkStatusFilter
import dev.claudefleet.mobile.model.sortTickets
import dev.claudefleet.mobile.model.ticketFacets
import dev.claudefleet.mobile.model.ticketMatchesQuery
import dev.claudefleet.mobile.model.ticketStatusNames
import dev.claudefleet.mobile.model.without
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.existingSessionId
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * One section of the Tickets sheet: a hub view and what it answered. In
 * [TicketsUiState.sections], [tickets] is what the filters let through, in
 * the chosen sort, and [total] is how many the hub listed — so a section can
 * say "Nothing here" apart from "3 hidden by filters".
 */
data class TicketSection(val view: String, val title: String, val tickets: List<Ticket>, val total: Int = tickets.size)

/** What a person can do with the ticket they tapped. */
data class TicketDetail(
    val ticket: Ticket,
    /** A session already on it: **Open** jumps there, and nothing starts a second one. */
    val liveSessionId: Long? = null,
    /** **Start here** — only with no live session, a write credential, and `work_link start`. */
    val canStart: Boolean = false,
    /** Past work the hub can pick up (`resume_plan` allows `last`), once the plan has answered. */
    val plan: ResumePlan? = null,
    val canResume: Boolean = false,
    /** Where a resume may go: the plan's reachable hosts, its own suggestion first. */
    val resumeHosts: List<String> = emptyList(),
    val resumeHost: String? = null,
    /**
     * The ticket's context card (`work card`, claude-fleet M9.2): its
     * acceptance criteria, or an excerpt — null until it answers, and on a
     * hub without the action or a key it has not cached.
     */
    val card: TicketCard? = null,
    /**
     * Past work on the key, newest first — the resume plan's candidates,
     * shown whether or not a session is live on it now: what was done
     * before is worth reading either way.
     */
    val pastWork: List<ResumeCandidate> = emptyList(),
    /**
     * Why Resume is not offered, in the hub's words ([resumeWhyNot]): the
     * plan's `last` mode said no, and why. Null when it said yes or gave
     * no reason, and while a session is live on the ticket.
     */
    val resumeWhyNot: String? = null,
    /** What is wrong with the ticket or its tracker, as plain text ([ticketTrouble]). */
    val trouble: List<String> = emptyList(),
)

/**
 * **Resume**, waiting on the person's yes: what it will pick up and where.
 * The tap only asks; [TicketsViewModel.confirmResume] is what calls the hub,
 * and it resumes exactly this key on exactly this host.
 */
data class ResumeConfirm(val key: String, val title: String, val host: String?)

data class TicketsUiState(
    /** The hub has the work graph at all; without it the sheet is never offered. */
    val available: Boolean = false,
    val open: Boolean = false,
    val sections: List<TicketSection> = emptyList(),
    val loading: Boolean = false,
    val query: String = "",
    /** What a search found — drawn above the sections. */
    val found: Ticket? = null,
    val selected: TicketDetail? = null,
    val busy: Boolean = false,
    val error: Friendly? = null,
    /** A Resume asked for and not yet confirmed or cancelled; the sheet asks while it is set. */
    val confirmResume: ResumeConfirm? = null,
    /**
     * Ticket id → the name of its org (by its tracker), when the hub has two
     * or more orgs; empty otherwise, which draws no org labels at all.
     */
    val ticketOrgs: Map<Long, String> = emptyMap(),
    /** What narrows the lists; remembered on the device, like the Sessions list's. */
    val filters: TicketFilters = TicketFilters(),
    /** How each section is ordered — a view, outside the filters and their count. */
    val sort: TicketSort = TicketSort.TRACKER,
    /** The sheet shows its filter page instead of the lists. */
    val filtersOpen: Boolean = false,
    /** Every filter that is on, the search text included: what the empty state names. */
    val facets: List<Facet<TicketFacetId>> = emptyList(),
    /**
     * Rows the sheet DRAWS, and how many the sections it is drawing hold
     * unfiltered: "5 of 23".
     *
     * Per section, summed — not distinct tickets. The hub's `views_of` makes
     * *Current sprint* and *Recent* subsets of *My work*, so a ticket is
     * routinely in two or three of them and is drawn once in each; every
     * section header counts that way, and so does the filter page's "Show N
     * tickets" button, which is a promise about what pressing it draws.
     * Deduping only these two made the headline disagree with the three
     * headers directly beneath it ("2 of 4" over "3 / 2 / 1") and with the
     * rows a person can count. The denominator is the KEPT sections' own
     * totals, so a list switched off is not counted either. The sibling
     * screen sums the same way (`SessionsViewModel`).
     */
    val shown: Int = 0,
    val total: Int = 0,
    /** The filter page's chips: the tracker columns, orgs and trackers the lists hold. */
    val statusNameChoices: List<String> = emptyList(),
    val orgChoices: List<OrgInfo> = emptyList(),
    val trackerChoices: List<TrackerRow> = emptyList(),
) {
    /** The strip's chips: every facet but the one the search field already shows. */
    val stripFacets: List<Facet<TicketFacetId>> get() = facets.filterNot { it.id.onScreen }

    /**
     * The lists hold tickets and the filters hide every one — the sheet says
     * so, and why.
     *
     * Never while a looked-up ticket is on screen. A key that is in none of
     * the three lists is what the search field is FOR, and it satisfies
     * `shown == 0`: the sheet drew the ticket, then "No tickets match
     * Search: …" directly beneath it, and that box's only button clears the
     * search — discarding the ticket the person had just found.
     */
    val allFiltered: Boolean get() = total > 0 && shown == 0 && facets.isNotEmpty() && found == null
}

/**
 * The Tickets sheet over the Sessions tab: *My work*, *Current sprint* and
 * *Recent* from the hub's tracker cache, and a search that takes a key or a
 * pasted URL. The phone is a pager, so this is a sheet and not a tab.
 *
 * Per ticket: **Open** when a session is already on it; otherwise **Start
 * here** (the New session form in ticket mode); and **Resume** when there is
 * past work, with a host picker and nothing else — the phone never edits the
 * hub's brief. Resume asks before it calls. A resume the hub refuses with
 * `E_EXISTS` opens the session it names instead.
 *
 * Every title and description here is third-party text: the screen draws it
 * as plain text only.
 */
class TicketsViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** Show a session — an Open, a resume, or an `E_EXISTS` jump. */
    private val onOpenSession: (Long) -> Unit,
    /** Open the New session form in ticket mode for this key. */
    private val onStartHere: (String) -> Unit,
    /** Where the filters and the sort are remembered; null remembers nothing. */
    private val prefs: Prefs? = null,
) {
    private data class Local(
        val filters: TicketFilters = TicketFilters(),
        val sort: TicketSort = TicketSort.TRACKER,
        val filtersOpen: Boolean = false,
        val open: Boolean = false,
        val sections: List<TicketSection> = emptyList(),
        val loading: Boolean = false,
        val query: String = "",
        val found: Ticket? = null,
        val selectedId: Long? = null,
        val selected: Ticket? = null,
        val plan: ResumePlan? = null,
        val card: TicketCard? = null,
        val resumeHost: String? = null,
        val confirmResume: ResumeConfirm? = null,
        val busy: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local(filters = storedFilters(), sort = storedSort()))

    val state: StateFlow<TicketsUiState> =
        combine(
            fleet.capabilities,
            fleet.tickets,
            fleet.sessions,
            combine(fleet.orgs, fleet.trackers, ::Pair),
            local,
        ) { caps, cache, sessions, (orgs, trackers), l ->
            assemble(caps, cache, sessions, orgs, trackers, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(
                fleet.capabilities.value,
                fleet.tickets.value,
                fleet.sessions.value,
                fleet.orgs.value,
                fleet.trackers.value,
                local.value,
            ),
        )

    /** Open the sheet and read the three views. */
    fun open(): Job? {
        if (!fleet.capabilities.value.has(WORK, TICKETS)) return null
        local.update { it.copy(open = true, loading = true, error = null) }
        return scope.launch {
            try {
                val sections = coroutineScope {
                    VIEWS.map { (view, title) -> async { TicketSection(view, title, actions.tickets(view)) } }
                        .map { it.await() }
                }
                fleet.rememberTickets(sections.flatMap { it.tickets })
                local.update { it.copy(sections = sections, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(loading = false, error = friendlyWork(t)) }
            }
        }
    }

    fun close() {
        local.update { Local(filters = it.filters, sort = it.sort, sections = it.sections) }
    }

    // ── Filters ──

    fun openFilters() {
        local.update { it.copy(filtersOpen = true) }
    }

    fun closeFilters() {
        local.update { it.copy(filtersOpen = false) }
    }

    /**
     * A list's chip. From *All*, the tap picks that list alone — the chip
     * reads unselected under *All*, so a tap must select it, not hide it.
     * After that it adds or removes the list; none left, or all three, is *All*.
     */
    fun toggleList(list: TicketList) = setFilters { f ->
        val next = when {
            f.lists.isEmpty() -> setOf(list)
            list in f.lists -> f.lists - list
            else -> f.lists + list
        }
        f.copy(lists = if (next.size == TicketList.entries.size) emptySet() else next)
    }

    fun toggleStatus(status: WorkStatusFilter) = setFilters { f ->
        f.copy(statuses = if (status in f.statuses) f.statuses - status else f.statuses + status)
    }

    /** A tracker column, matched case-insensitively, so "code review" and "Code Review" are one chip. */
    fun toggleStatusName(name: String) = setFilters { f ->
        val present = f.statusNames.firstOrNull { it.equals(name, ignoreCase = true) }
        f.copy(statusNames = if (present != null) f.statusNames - present else f.statusNames + name)
    }

    fun setOrg(id: Long?) = setFilters { it.copy(org = id) }

    fun setTracker(id: Long?) = setFilters { it.copy(tracker = id) }

    fun setSession(filter: TicketSessionFilter?) = setFilters { it.copy(session = filter) }

    /** One chip's ×: that filter back at its default. The search chip clears the field. */
    fun clearFacet(id: TicketFacetId) {
        if (id == TicketFacetId.SEARCH) local.update { it.copy(query = "", found = null) } else setFilters { it.without(id) }
    }

    /** Every filter off and the search field empty: every listed ticket back. */
    fun clearAll() {
        local.update { it.copy(query = "", found = null) }
        setFilters { TicketFilters() }
    }

    /** Tracker → Status → Updated → Tracker. Remembered, like the filters. */
    fun cycleSort() {
        var next = TicketSort.TRACKER
        local.update {
            next = it.sort.next
            it.copy(sort = next)
        }
        prefs?.putStringList(SORT_KEY, listOf(next.name))
    }

    /**
     * Change the filters and remember them. One `update {}`, with what it
     * committed read after, so two quick taps cannot both start from the
     * same value and lose one.
     */
    private fun setFilters(change: (TicketFilters) -> TicketFilters) {
        var stored = TicketFilters()
        local.update {
            stored = change(it.filters)
            it.copy(filters = stored)
        }
        prefs?.putStringList(FILTERS_KEY, listOf(json.encodeToString(TicketFilters.serializer(), stored)))
    }

    /** What was remembered; anything unreadable — an older or newer shape — is no filter at all. */
    private fun storedFilters(): TicketFilters {
        val stored = prefs?.getStringList(FILTERS_KEY)?.firstOrNull() ?: return TicketFilters()
        return try {
            json.decodeFromString(TicketFilters.serializer(), stored)
        } catch (_: Exception) {
            TicketFilters()
        }
    }

    private fun storedSort(): TicketSort {
        val stored = prefs?.getStringList(SORT_KEY)?.firstOrNull() ?: return TicketSort.TRACKER
        return TicketSort.entries.firstOrNull { it.name == stored } ?: TicketSort.TRACKER
    }

    fun onQuery(text: String) {
        local.update { it.copy(query = text) }
    }

    /** A key such as `PAY-9` or a pasted ticket URL: the hub's cache, else one live fetch. */
    fun search(): Job? {
        val reference = local.value.query.trim()
        if (reference.isEmpty() || !fleet.capabilities.value.has(WORK, LOOKUP)) return null
        return scope.launch {
            local.update { it.copy(busy = true, error = null, found = null) }
            try {
                val ticket = actions.lookup(reference)
                fleet.rememberTickets(listOf(ticket))
                local.update { it.copy(found = ticket) }
                select(ticket)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, LOOKUP)
                local.update { it.copy(error = friendlyWork(t)) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Show one ticket's actions, and ask the hub whether it has past work to
     * resume. The plan is a read (`work resume_plan`); a hub that answers
     * anything else — an older one's plain link list fails to parse — simply
     * offers no Resume.
     */
    fun select(ticket: Ticket?): Job? {
        local.update {
            it.copy(selectedId = ticket?.id, selected = ticket, plan = null, card = null, resumeHost = null, confirmResume = null)
        }
        val key = ticket?.key ?: return null
        val caps = fleet.capabilities.value
        return scope.launch {
            // The card and the plan are independent reads; neither waits on
            // the other, and either failing leaves the other standing.
            if (caps.has(WORK, CARD)) {
                launch {
                    val card = readOrNull(CARD) { actions.card(key) }?.takeIf { it.cached }
                    local.update { if (it.selectedId == ticket.id) it.copy(card = card) else it }
                }
            }
            if (caps.has(WORK, RESUME_PLAN)) {
                val plan = readOrNull(RESUME_PLAN) { actions.resumePlan(key) }
                local.update { if (it.selectedId == ticket.id) it.copy(plan = plan, resumeHost = plan?.hostAlias) else it }
            }
        }
    }

    /**
     * A read whose failure means "nothing to show": an older hub's answer
     * that does not parse, a key with no past work. An action the hub does
     * not know is forgotten for the connection.
     */
    private suspend fun <T> readOrNull(action: String, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, action)
        null
    }

    fun selectResumeHost(alias: String) {
        local.update { it.copy(resumeHost = alias, confirmResume = null) }
    }

    /** **Open**: the session already on the ticket. */
    fun openLive() {
        val id = state.value.selected?.liveSessionId ?: return
        close()
        onOpenSession(id)
    }

    /** **Start here**: the New session form, in ticket mode. */
    fun startHere() {
        val detail = state.value.selected ?: return
        if (!detail.canStart) return
        val key = detail.ticket.key ?: return
        close()
        onStartHere(key)
    }

    /**
     * **Resume**: ask first. Resuming starts a session on a host, so the tap
     * only puts up the question — which key, on which host — and
     * [confirmResume] is what calls the hub.
     */
    fun resume() {
        val detail = state.value.selected ?: return
        val key = detail.ticket.key ?: return
        if (!detail.canResume || local.value.busy) return
        local.update { it.copy(confirmResume = ResumeConfirm(key, detail.ticket.title, it.resumeHost)) }
    }

    /** The person said no: nothing is resumed. */
    fun cancelResume() {
        local.update { it.copy(confirmResume = null) }
    }

    /**
     * The person said yes: resume the last conversation on the key and host
     * the question named. It runs in this view model's scope, which the
     * caller keeps as the fleet's — backing out never cancels a resume half
     * made. Checked again here: a ticket that has since gone live, or a
     * token that may not, resumes nothing.
     */
    fun confirmResume(): Job? {
        val ask = local.value.confirmResume ?: return null
        local.update { it.copy(confirmResume = null) }
        val detail = state.value.selected ?: return null
        if (detail.ticket.key != ask.key || !detail.canResume || local.value.busy) return null
        local.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val row = actions.resume(ask.key, ask.host)
                local.update { it.copy(busy = false) }
                close()
                onOpenSession(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val jump = (t as? HubError.Tool)?.existingSessionId()
                if (jump != null) {
                    local.update { it.copy(busy = false) }
                    close()
                    onOpenSession(jump)
                } else {
                    if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, RESUME)
                    local.update { it.copy(busy = false, error = friendlyWork(t)) }
                }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun assemble(
        caps: HubCapabilities,
        cache: List<Ticket>,
        sessions: List<SessionRow>,
        orgs: OrgDirectory,
        trackers: List<TrackerRow>,
        l: Local,
    ): TicketsUiState {
        if (!caps.work) return TicketsUiState()
        // The cache is newer than a listing whenever a `work:item` frame has
        // landed since; the listing still decides which tickets are shown.
        val fresh = cache.associateBy { it.id }
        fun Ticket.current() = fresh[id]?.copy(liveSessionIds = liveSessionIds, views = views, description = description) ?: this
        val selected = (fresh[l.selectedId] ?: l.selected)?.let { ticket ->
            // Whoever knows of a live session wins: the fleet's own rows, the
            // ticket's listing, or the plan. Any one of them means Jump —
            // but the listing and the plan are snapshots from when the sheet
            // read them, so an id they name counts only while the fleet still
            // has that session. A session killed with the sheet open must turn
            // Open back into Resume, not jump to a row that is gone.
            val alive = sessions.mapTo(HashSet()) { it.id }
            val live = sessions.firstOrNull { it.work?.itemId == ticket.id }?.id
                ?: ticket.liveSessionIds.firstOrNull { it in alive }
                ?: l.plan?.live?.firstOrNull { it.sessionId in alive }?.sessionId
            val plan = l.plan?.takeIf { live == null }
            TicketDetail(
                ticket = ticket,
                liveSessionId = live,
                canStart = live == null && ticket.key != null && canWrite && caps.has(WORK_LINK, START),
                plan = plan,
                canResume = plan?.canResumeLast == true && canWrite && caps.has(WORK_LINK, RESUME),
                resumeHosts = plan?.let { p -> (listOfNotNull(p.hostAlias) + p.hosts).distinct() }.orEmpty(),
                resumeHost = l.resumeHost,
                card = l.card,
                pastWork = l.plan?.candidates.orEmpty().sortedByDescending { it.endedAt ?: Long.MIN_VALUE },
                resumeWhyNot = resumeWhyNot(plan),
                trouble = ticketTrouble(ticket, trackers),
            )
        }
        val shown = l.sections.flatMap { it.tickets } + listOfNotNull(l.found, l.selected)
        val ticketOrgs = if (orgs.orgs.size < 2) {
            emptyMap()
        } else {
            shown.mapNotNull { t -> orgs.orgOf(t)?.let { t.id to orgs.name(it) } }.toMap()
        }

        // The filters, over the listings as the cache now has them. The same
        // liveness rule as the detail's: a session the fleet still has.
        val alive = sessions.mapTo(HashSet()) { it.id }
        val liveItems = sessions.mapNotNullTo(HashSet()) { it.work?.itemId }
        val live = { t: Ticket -> t.id in liveItems || t.liveSessionIds.any { it in alive } }
        // APPLIED filters, which is not the same as the DISPLAYED ones. The
        // Organisation filter is the only predicate answered from the org
        // DIRECTORY rather than from the row, and `readOrgs` replaces a good
        // directory with EMPTY on any failure — after which `orgOf` is null
        // for every ticket and the org filter hid the whole sheet, blaming a
        // filter the person set long ago. One flaky read did it, and with the
        // filters now persisted it never healed for the connection. The
        // repository states the rule one function below (`readMyWork`: a
        // failed read "hides the chip rather than filtering the list to
        // nothing") and the sibling screen already splits the two
        // (`SessionsViewModel`). `l.filters` still goes to the state, so the
        // chip stays visible and clearable.
        val f = if (orgs.trackerOrg.isEmpty()) l.filters.copy(org = null) else l.filters
        val listed = l.sections.map { s -> s.copy(tickets = s.tickets.map { it.current() }) }
        val all = listed.flatMap { it.tickets }
        val sections = listed
            .filter { s -> f.lists.isEmpty() || TicketList.of(s.view)?.let { it in f.lists } != false }
            .map { s ->
                val kept = s.tickets.filter { t -> f.matches(t, orgs::orgOf, live) && ticketMatchesQuery(t, l.query) }
                s.copy(tickets = sortTickets(kept, l.sort), total = s.tickets.size)
            }

        val orgIds = all.mapNotNullTo(LinkedHashSet()) { orgs.orgOf(it) }
        // The org the PERSON set, not the applied one: the chip has to stay on
        // screen, and clearable, while the directory is empty.
        val chosenOrg = l.filters.org
        val orgChoices = if (orgs.orgs.size < 2 && chosenOrg == null) {
            emptyList()
        } else {
            (orgIds + listOfNotNull(chosenOrg)).map { id -> orgs.orgs[id] ?: OrgInfo(id, orgs.name(id)) }
                .sortedBy { it.name.lowercase() }
        }
        val trackerIds = all.mapNotNullTo(LinkedHashSet()) { it.trackerId } + listOfNotNull(f.tracker)
        val trackerChoices = trackerIds
            .map { id -> trackers.firstOrNull { it.id == id } ?: TrackerRow(id = id, name = "Tracker #$id") }
            .sortedBy { trackerName(it).lowercase() }
        return TicketsUiState(
            available = caps.has(WORK, TICKETS),
            open = l.open,
            sections = sections,
            loading = l.loading,
            query = l.query,
            found = l.found?.current(),
            selected = selected,
            busy = l.busy,
            error = l.error,
            confirmResume = l.confirmResume?.takeIf { selected?.canResume == true && selected.ticket.key == it.key },
            ticketOrgs = ticketOrgs,
            filters = l.filters,
            sort = l.sort,
            filtersOpen = l.filtersOpen,
            facets = ticketFacets(
                l.filters,
                l.query,
                orgName = { id -> orgs.name(id) },
                trackerName = { id -> trackerChoices.firstOrNull { it.id == id }?.let(::trackerName) },
            ),
            shown = sections.sumOf { it.tickets.size },
            total = sections.sumOf { it.total },
            statusNameChoices = ticketStatusNames(all),
            orgChoices = orgChoices,
            trackerChoices = if (trackerChoices.size > 1 || f.tracker != null) trackerChoices else emptyList(),
        )
    }

    private companion object {
        const val TICKETS = "tickets"
        const val CARD = "card"
        const val LOOKUP = "lookup"
        const val RESUME_PLAN = "resume_plan"
        const val START = "start"
        const val RESUME = "resume"
        val VIEWS = TicketList.entries.map { it.wire to it.label }
        const val FILTERS_KEY = "tickets.filters.v1"
        const val SORT_KEY = "tickets.sort"
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** What a tracker chip reads: its name, else its provider. */
internal fun trackerName(t: TrackerRow): String = t.name.ifBlank { t.provider.ifBlank { "Tracker #${t.id}" } }
