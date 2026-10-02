package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.TodayFilters
import dev.claudefleet.mobile.model.TodaySection
import dev.claudefleet.mobile.model.TodayView
import dev.claudefleet.mobile.model.count
import dev.claudefleet.mobile.model.filterToday
import dev.claudefleet.mobile.model.localMidnight
import dev.claudefleet.mobile.model.orgOf
import dev.claudefleet.mobile.model.scopeToday
import dev.claudefleet.mobile.model.standupText
import dev.claudefleet.mobile.model.todayHosts
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.utcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodayUiState(
    /** The hub serves `work today`: the Sessions header offers the sheet. */
    val available: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    /** The digest, cut to the Sessions list's org filter. */
    val view: TodayView = TodayView(),
    /** Whether a digest has answered at all — "nothing today" is only said after one has. */
    val loaded: Boolean = false,
    val error: Friendly? = null,
    /** The sheet's own chips: sections, host, tickets only. */
    val filters: TodayFilters = TodayFilters(),
    /** [view] through [filters]: what the sheet draws. */
    val shown: TodayView = TodayView(),
    /**
     * Each section's count with every filter but the section chips applied,
     * so a chip says what tapping it would show. A section at 0 is offered
     * only while it is selected — the one chip that can clear it.
     */
    val sectionCounts: Map<TodaySection, Int> = emptyMap(),
    /** The hosts [view]'s sessions run on, plus the filtered one if it has gone. */
    val hostChoices: List<String> = emptyList(),
    /** What *Copy standup* and *Share* hand on — see [standupFor]. Built once
     *  per state in `assemble`, not on read: the sheet uses it as a `remember`
     *  key, and a computed `get()` rebuilt the whole text (every group, every
     *  session, every shipped line) on every recomposition and then compared it
     *  to decide whether anything changed. */
    val standup: String = "",
) {

}

/**
 * The standup text for one state: what is on screen, and the whole day's when
 * the chips have hidden everything.
 *
 * `standup` was repointed from `view` to `shown` so a filtered copy matches the
 * list, which is right; but `shown` empty means the filters match nothing, and
 * copying an EMPTY standup is never what the button is for. The list says
 * "Nothing today matches these filters" and offers Clear; the text falls back
 * to the day.
 */
internal fun standupFor(view: TodayView, shown: TodayView): String =
    standupText(if (shown.isEmpty) view else shown)

/**
 * The Today sheet over the Sessions tab (claude-fleet M9.1 on the phone): the
 * day's work since local midnight in the desktop's sections — *Waiting on
 * me*, *In progress*, *Shipped*, *Stale* — and **Copy standup**.
 *
 * The hub builds the digest (`work { action: today }`) and buckets it; the
 * phone narrows it to the Sessions list's org filter ([orgFilter]) and
 * re-buckets with the hub's own rule, as the desktop does for its scope. A
 * read, open to a readonly token.
 *
 * On top of that the sheet has chips of its own ([TodayFilters]: sections,
 * a host, tickets only), held here so they survive the sheet closing and
 * reopening; *Copy standup* copies what they leave on screen.
 *
 * While the sheet is open it re-reads when the fleet moves — a session row
 * changing, or a ticket (`work:item`) — at most once per [refreshDebounceMs],
 * since one turn ending can send a burst of frames.
 */
@OptIn(FlowPreview::class)
class TodayViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    /** The Sessions list's org filter; the sheet shows the same slice of the fleet. */
    private val orgFilter: StateFlow<Long?>,
    /** Show a session the sheet lists. */
    private val onOpenSession: (Long) -> Unit,
    /** This device's clock, unix seconds. */
    private val clock: () -> Long = { epochSeconds() },
    /** How far local time is ahead of UTC at a moment, in seconds. */
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
    private val refreshDebounceMs: Long = 2_000,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val today: Today? = null,
        val error: Friendly? = null,
        val filters: TodayFilters = TodayFilters(),
    )

    private val local = MutableStateFlow(Local())
    private var follow: Job? = null

    val state: StateFlow<TodayUiState> =
        combine(fleet.capabilities, fleet.sessions, orgFilter, local) { caps, rows, org, l ->
            assemble(caps, rows, org, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(fleet.capabilities.value, fleet.sessions.value, orgFilter.value, local.value),
        )

    /** Open the sheet, read the digest, and keep it current while open. */
    fun open(): Job? {
        if (!fleet.capabilities.value.has(WORK, TODAY)) return null
        local.update { it.copy(open = true, error = null) }
        follow?.cancel()
        follow = scope.launch {
            merge(fleet.sessionChanges.map { }, fleet.tickets.drop(1).map { })
                .debounce(refreshDebounceMs)
                .collect { load() }
        }
        return scope.launch { load() }
    }

    fun close() {
        follow?.cancel()
        follow = null
        local.update { it.copy(open = false, loading = false, error = null) }
    }

    /** A pull on the sheet, or a retry after an error. */
    fun refresh(): Job = scope.launch { load() }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** A section chip: several at once, none meaning every section. */
    fun toggleSection(section: TodaySection) {
        local.update { l ->
            val on = l.filters.sections
            l.copy(filters = l.filters.copy(sections = if (section in on) on - section else on + section))
        }
    }

    /** A host chip; the selected one again, or null, clears it. */
    fun setHost(host: String?) {
        local.update { l -> l.copy(filters = l.filters.copy(host = if (host == l.filters.host) null else host)) }
    }

    fun toggleTicketsOnly() {
        local.update { l -> l.copy(filters = l.filters.copy(ticketsOnly = !l.filters.ticketsOnly)) }
    }

    fun clearFilters() {
        local.update { it.copy(filters = TodayFilters()) }
    }

    /** Show [sessionId]: the sheet closes first, as the Tickets sheet's Open does. */
    fun openSession(sessionId: Long) {
        close()
        onOpenSession(sessionId)
    }

    private suspend fun load() {
        if (!fleet.capabilities.value.has(WORK, TODAY)) return
        local.update { it.copy(loading = true) }
        val now = clock()
        try {
            val today = actions.today(localMidnight(now, utcOffset(now)))
            local.update { it.copy(loading = false, today = today, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, TODAY)
            local.update { it.copy(loading = false, error = friendlyWork(t)) }
        }
    }

    private fun assemble(caps: HubCapabilities, rows: List<SessionRow>, org: Long?, l: Local): TodayUiState {
        val available = caps.has(WORK, TODAY)
        if (!available) return TodayUiState()
        // Nothing is drawn while the sheet is closed, and this runs on every
        // session-row emission for the whole fleet: one `associateBy`, a
        // `scopeToday`, two `filterToday` passes and a `todayHosts` over the
        // digest, all to build a state nobody reads. The filters are kept —
        // they survive the sheet closing, which is the documented lifecycle.
        if (!l.open) {
            return TodayUiState(
                available = true,
                open = false,
                loaded = l.today != null,
                filters = l.filters,
            )
        }
        val byId = rows.associateBy { it.id }
        val view = l.today?.let { t -> scopeToday(t, org) { s -> byId[s.id]?.orgOf ?: s.orgId } } ?: TodayView()
        val f = l.filters
        val unsectioned = filterToday(view, f.copy(sections = emptySet()))
        val hosts = todayHosts(view)
        val shown = filterToday(view, f)
        return TodayUiState(
            available = true,
            open = l.open,
            loading = l.loading,
            view = view,
            loaded = l.today != null,
            error = l.error,
            filters = f,
            shown = shown,
            sectionCounts = TodaySection.entries.associateWith { unsectioned.count(it) },
            hostChoices = if (f.host != null && f.host !in hosts) (hosts + f.host).sorted() else hosts,
            standup = standupFor(view, shown),
        )
    }

    private companion object {
        const val TODAY = "today"
    }
}
