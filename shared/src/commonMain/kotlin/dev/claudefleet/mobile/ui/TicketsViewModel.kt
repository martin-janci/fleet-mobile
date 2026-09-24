package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.existingSessionId
import dev.claudefleet.mobile.net.isUnknownAction
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

/** One section of the Tickets sheet: a hub view and what it answered. */
data class TicketSection(val view: String, val title: String, val tickets: List<Ticket>)

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
)

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
)

/**
 * The Tickets sheet over the Sessions tab: *My work*, *Current sprint* and
 * *Recent* from the hub's tracker cache, and a search that takes a key or a
 * pasted URL. The phone is a pager, so this is a sheet and not a tab.
 *
 * Per ticket: **Open** when a session is already on it; otherwise **Start
 * here** (the New session form in ticket mode); and **Resume** when there is
 * past work, with a host picker and nothing else — the phone never edits the
 * hub's brief. A resume the hub refuses with `E_EXISTS` opens the session it
 * names instead.
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
) {
    private data class Local(
        val open: Boolean = false,
        val sections: List<TicketSection> = emptyList(),
        val loading: Boolean = false,
        val query: String = "",
        val found: Ticket? = null,
        val selectedId: Long? = null,
        val selected: Ticket? = null,
        val plan: ResumePlan? = null,
        val resumeHost: String? = null,
        val busy: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<TicketsUiState> =
        combine(fleet.capabilities, fleet.tickets, fleet.sessions, local) { caps, cache, sessions, l ->
            assemble(caps, cache, sessions, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(fleet.capabilities.value, fleet.tickets.value, fleet.sessions.value, local.value),
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
        local.update { Local(sections = it.sections) }
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
        local.update { it.copy(selectedId = ticket?.id, selected = ticket, plan = null, resumeHost = null) }
        val key = ticket?.key ?: return null
        if (!fleet.capabilities.value.has(WORK, RESUME_PLAN)) return null
        return scope.launch {
            val plan = try {
                actions.resumePlan(key)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, RESUME_PLAN)
                null
            }
            local.update { if (it.selectedId == ticket.id) it.copy(plan = plan, resumeHost = plan?.hostAlias) else it }
        }
    }

    fun selectResumeHost(alias: String) {
        local.update { it.copy(resumeHost = alias) }
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
     * **Resume** the last conversation on the chosen host. It runs in this
     * view model's scope, which the caller keeps as the fleet's — backing out
     * never cancels a resume half made.
     */
    fun resume(): Job? {
        val detail = state.value.selected ?: return null
        val key = detail.ticket.key ?: return null
        if (!detail.canResume || local.value.busy) return null
        val host = local.value.resumeHost
        local.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val row = actions.resume(key, host)
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

    private fun assemble(caps: HubCapabilities, cache: List<Ticket>, sessions: List<SessionRow>, l: Local): TicketsUiState {
        if (!caps.work) return TicketsUiState()
        // The cache is newer than a listing whenever a `work:item` frame has
        // landed since; the listing still decides which tickets are shown.
        val fresh = cache.associateBy { it.id }
        fun Ticket.current() = fresh[id]?.copy(liveSessionIds = liveSessionIds, views = views, description = description) ?: this
        val selected = (fresh[l.selectedId] ?: l.selected)?.let { ticket ->
            // Whoever knows of a live session wins: the fleet's own rows, the
            // ticket's listing, or the plan. Any one of them means Jump.
            val live = sessions.firstOrNull { it.work?.itemId == ticket.id }?.id
                ?: ticket.liveSessionIds.firstOrNull()
                ?: l.plan?.live?.firstOrNull()?.sessionId
            val plan = l.plan?.takeIf { live == null }
            TicketDetail(
                ticket = ticket,
                liveSessionId = live,
                canStart = live == null && ticket.key != null && canWrite && caps.has(WORK_LINK, START),
                plan = plan,
                canResume = plan?.canResumeLast == true && canWrite && caps.has(WORK_LINK, RESUME),
                resumeHosts = plan?.let { p -> (listOfNotNull(p.hostAlias) + p.hosts).distinct() }.orEmpty(),
                resumeHost = l.resumeHost,
            )
        }
        return TicketsUiState(
            available = caps.has(WORK, TICKETS),
            open = l.open,
            sections = l.sections.map { s -> s.copy(tickets = s.tickets.map { it.current() }) },
            loading = l.loading,
            query = l.query,
            found = l.found?.current(),
            selected = selected,
            busy = l.busy,
            error = l.error,
        )
    }

    private companion object {
        const val TICKETS = "tickets"
        const val LOOKUP = "lookup"
        const val RESUME_PLAN = "resume_plan"
        const val START = "start"
        const val RESUME = "resume"
        val VIEWS = listOf("mine" to "My work", "sprint" to "Current sprint", "recent" to "Recent")
    }
}
