package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SessionTasksUiState(
    /** The hub serves `work { session_tasks }`: the *Tasks* chip is drawn. */
    val available: Boolean = false,
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val sheetOpen: Boolean = false,
    /** Live links, the primary first. */
    val active: List<WorkTaskLink> = emptyList(),
    val suggested: List<WorkTaskLink> = emptyList(),
    /** Ended and rejected links — never shown as active. */
    val past: List<WorkTaskLink> = emptyList(),
    val primaryLinkId: Long? = null,
    val connected: Boolean = false,
    val canMakePrimary: Boolean = false,
    val canRemove: Boolean = false,
    val canConfirm: Boolean = false,
    val canReject: Boolean = false,
    /** **Add task…**: `work_link link` with a full token, connected. */
    val canAdd: Boolean = false,
    val addOpen: Boolean = false,
    val addQuery: String = "",
    /** Tickets the add sheet offers, narrowed by [addQuery]; ones already linked are left out. */
    val addCandidates: List<Ticket> = emptyList(),
    val busy: Boolean = false,
    val error: Friendly? = null,
    /** The error is a write another device beat: *Reload* is offered. */
    val conflict: Boolean = false,
) {
    /** What the chip counts: the tasks this session is on, or may be on. */
    val count: Int get() = active.size + suggested.size
}

/**
 * The *Tasks* section of a session screen: every link the session has
 * (`work { session_tasks }`) — live, suggested and past — and the corrections
 * a person makes to them.
 *
 * - **Make primary**: `set_primary`, a compare-and-set against the primary
 *   this screen last read ([SessionTasksUiState.primaryLinkId], `0` for
 *   none). Another device moving it first is `E_CONFLICT`, said as such with
 *   *Reload*; nothing is overwritten.
 * - **Remove**: `unlink` under the link's `link_version`.
 * - **Add task…**: a searchable list (the ticket cache, *My work* and
 *   *Recent*) or a typed key; linked as the primary only when the links are
 *   read and the session has none, otherwise as a *secondary* link — so
 *   adding never moves a primary, even one this screen has not read yet.
 *
 * Nothing is shown as saved until the hub has answered; the screen then
 * re-reads the links (the session row moves by its own frame, as ever).
 * Writes run in [callScope], which outlives the screen, so leaving the
 * session does not cancel one half way; the re-read after it happens only
 * while this screen's [scope] is alive.
 */
class SessionTasksViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val onOpenTask: (String) -> Unit = {},
    private val refreshDebounceMs: Long = 1_000,
    /** Where writes run: the fleet's scope, not this screen's. */
    private val callScope: CoroutineScope = scope,
) {
    private data class Local(
        val loading: Boolean = false,
        val tasks: SessionTasks? = null,
        val sheetOpen: Boolean = false,
        val addOpen: Boolean = false,
        val addQuery: String = "",
        val offered: List<Ticket> = emptyList(),
        val busy: Boolean = false,
        val error: Friendly? = null,
        val conflict: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SessionTasksUiState> =
        combine(fleet.capabilities, fleet.status, fleet.tickets, local) { caps, status, cache, l -> assemble(caps, status, cache, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, fleet.tickets.value, local.value))

    init {
        scope.launch {
            combine(fleet.capabilities, fleet.status) { caps, status -> caps.has(WORK, SESSION_TASKS) && status.isConnected() }
                .distinctUntilChanged()
                .collect { if (it) load() }
        }
        scope.launch {
            // This session's work signature only (its links' `work_rev`
            // above all), never its status churn; `work:*` frames apart, so
            // neither holds the other back.
            merge(
                fleet.workSignatureChanges { it.id == sessionId }.throttleLatest(refreshDebounceMs),
                fleet.workChanges.throttleLatest(refreshDebounceMs),
            )
                .collect { if (fleet.status.value.isConnected()) load() }
        }
    }

    /** Re-read the links — *Reload* after a conflict (which it clears), or a pull. */
    fun reload(): Job = scope.launch {
        local.update { it.copy(error = null, conflict = false) }
        load()
    }

    private suspend fun load() {
        if (!fleet.capabilities.value.has(WORK, SESSION_TASKS)) return
        local.update { it.copy(loading = true) }
        try {
            val tasks = actions.sessionTasks(sessionId)
            local.update { it.copy(loading = false, tasks = tasks) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, SESSION_TASKS)
            local.update { it.copy(loading = false, error = friendlyWork(t), conflict = false) }
        }
    }

    fun openSheet() {
        local.update { it.copy(sheetOpen = true) }
    }

    fun closeSheet() {
        local.update { it.copy(sheetOpen = false, addOpen = false, addQuery = "") }
    }

    fun dismissError() {
        local.update { it.copy(error = null, conflict = false) }
    }

    /** **Open task**: the task's own screen. */
    fun openTask(link: WorkTaskLink) {
        val id = link.task?.taskId?.takeIf { it.isNotBlank() } ?: return
        closeSheet()
        onOpenTask(id)
    }

    /** **Make primary**, against the primary this screen last saw (`0` = none). */
    fun makePrimary(link: WorkTaskLink): Job? {
        if (link.state != LinkState.Active || link.primary) return null
        val expected = local.value.tasks?.primaryLinkId ?: 0L
        return write(SET_PRIMARY) { actions.setPrimary(sessionId, link.linkId, expectedPrimary = expected) }
    }

    /** **Remove**: clear a live link, under its version. */
    fun remove(link: WorkTaskLink): Job? {
        if (link.state != LinkState.Active) return null
        return write(UNLINK) { actions.unlink(sessionId, link.linkId, expectedVersion = link.linkVersion) }
    }

    /** Accept a suggestion — as the primary only when the session has none. */
    fun confirm(link: WorkTaskLink): Job? {
        if (link.state != LinkState.Suggested) return null
        val primary = takesPrimary()
        return write(CONFIRM) { actions.confirm(sessionId, link.linkId, primary = primary, expectedVersion = link.linkVersion) }
    }

    /** "Not this" for a suggestion, under its version. */
    fun reject(link: WorkTaskLink): Job? {
        if (link.state != LinkState.Suggested) return null
        return write(REJECT) { actions.reject(sessionId, link.linkId, expectedVersion = link.linkVersion) }
    }

    /** **Add task…**: open the list, and read *My work* and *Recent* into it. */
    fun openAdd(): Job? {
        if (!state.value.canAdd) return null
        local.update { it.copy(addOpen = true, addQuery = "") }
        if (!fleet.capabilities.value.has(WORK, TICKETS)) return null
        return scope.launch {
            val offered = try {
                coroutineScope {
                    listOf(MINE, RECENT).map { view -> async { actions.tickets(view) } }.flatMap { it.await() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, TICKETS)
                emptyList()
            }
            fleet.rememberTickets(offered)
            local.update { it.copy(offered = offered) }
        }
    }

    fun closeAdd() {
        local.update { it.copy(addOpen = false, addQuery = "") }
    }

    fun setAddQuery(text: String) {
        local.update { it.copy(addQuery = text) }
    }

    /** Link a ticket from the list. */
    fun add(ticket: Ticket): Job? = write(LINK) {
        actions.link(sessionId, itemId = ticket.id, primary = takesPrimary())
        local.update { it.copy(addOpen = false, addQuery = "") }
    }

    /**
     * Link what was typed: a key as a bare key, a pasted ticket URL only by
     * the ticket it resolves to (the hub would otherwise take the URL as a
     * free-form key and name a group after it).
     */
    fun addTyped(): Job? {
        val reference = local.value.addQuery.trim()
        if (reference.isEmpty()) return null
        return write(LINK) {
            if (reference.contains("://")) {
                val cannot = Friendly("This hub can't look up a ticket link", "Type the ticket's key instead, like PAY-7.", isError = true)
                if (!fleet.capabilities.value.has(WORK, LOOKUP)) {
                    local.update { it.copy(error = cannot) }
                    return@write
                }
                // The lookup's own refusal is answered here: an "unknown
                // action" from it is `work lookup` missing, never `work_link
                // link`, which [write] would otherwise hide.
                val ticket = try {
                    actions.lookup(reference)
                } catch (e: HubError.Tool) {
                    if (e.isUnknownAction()) {
                        fleet.actionMissing(WORK, LOOKUP)
                        local.update { it.copy(error = cannot) }
                    } else {
                        local.update { it.copy(error = friendlyWork(e)) }
                    }
                    return@write
                }
                actions.link(sessionId, itemId = ticket.id, primary = takesPrimary())
            } else {
                actions.link(sessionId, key = reference, primary = takesPrimary())
            }
            local.update { it.copy(addOpen = false, addQuery = "") }
        }
    }

    /**
     * Whether a link made now takes the primary: only when this screen has
     * read the session's links and it has none. Before they are read the
     * answer is unknown, and an unknown is never a reason to move someone's
     * primary — the link is made secondary, and *Make primary* is one tap.
     * Always sent explicitly: a `link` / `confirm` without `primary` takes
     * the primary on the hub.
     */
    private fun takesPrimary(): Boolean = sessionHasNoPrimary(local.value.tasks)

    private fun write(action: String, call: suspend () -> Unit): Job? {
        if (local.value.busy) return null
        if (!allowed(fleet.capabilities.value, fleet.status.value, action)) {
            if (canWrite && fleet.capabilities.value.has(WORK_LINK, action) && !fleet.status.value.isConnected()) {
                local.update { it.copy(error = OFFLINE_WRITE, conflict = false) }
            }
            return null
        }
        local.update { it.copy(busy = true, error = null, conflict = false) }
        return callScope.launch {
            try {
                call()
                // The re-read belongs to the screen: none once it is gone.
                if (scope.isActive) scope.launch { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, action)
                local.update { it.copy(error = friendlyWorkWrite(t), conflict = t.isConflict()) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun allowed(caps: HubCapabilities, status: ConnectionStatus, action: String): Boolean =
        canWrite && status.isConnected() && caps.has(WORK_LINK, action)

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, cache: List<Ticket>, l: Local): SessionTasksUiState {
        if (!caps.has(WORK, SESSION_TASKS)) return SessionTasksUiState()
        val links = l.tasks?.links.orEmpty()
        val primary = l.tasks?.primaryLinkId
        val active = links.filter { it.state == LinkState.Active }.sortedByDescending { it.primary || it.linkId == primary }
        val suggested = links.filter { it.state == LinkState.Suggested }
        val past = links.filter { it.state == LinkState.Ended || it.state == LinkState.Rejected }
            .sortedByDescending { it.endedAt ?: it.decidedAt ?: Long.MIN_VALUE }
        val linkedItems = links.filter { it.state == LinkState.Active || it.state == LinkState.Suggested }
            .mapNotNull { it.task?.taskId?.removePrefix(ITEM_PREFIX)?.toLongOrNull() }.toSet()
        val query = l.addQuery.trim()
        val candidates = (l.offered + cache).distinctBy { it.id }
            .filter { it.id !in linkedItems }
            .filter { t -> query.isEmpty() || t.label.contains(query, ignoreCase = true) || t.title.contains(query, ignoreCase = true) }
            .take(MAX_CANDIDATES)
        return SessionTasksUiState(
            available = true,
            loaded = l.tasks != null,
            loading = l.loading,
            sheetOpen = l.sheetOpen,
            active = active,
            suggested = suggested,
            past = past,
            primaryLinkId = primary,
            connected = status.isConnected(),
            canMakePrimary = allowed(caps, status, SET_PRIMARY),
            canRemove = allowed(caps, status, UNLINK),
            canConfirm = allowed(caps, status, CONFIRM),
            canReject = allowed(caps, status, REJECT),
            canAdd = allowed(caps, status, LINK),
            addOpen = l.addOpen,
            addQuery = l.addQuery,
            addCandidates = candidates,
            busy = l.busy,
            error = l.error,
            conflict = l.conflict,
        )
    }

    private companion object {
        const val SESSION_TASKS = "session_tasks"
        const val SET_PRIMARY = "set_primary"
        const val UNLINK = "unlink"
        const val CONFIRM = "confirm"
        const val REJECT = "reject"
        const val LINK = "link"
        const val LOOKUP = "lookup"
        const val TICKETS = "tickets"
        const val MINE = "mine"
        const val RECENT = "recent"
        const val ITEM_PREFIX = "item:"
        const val MAX_CANDIDATES = 50
    }
}

/**
 * The session's links are read ([tasks] non-null) and none of them is its
 * primary. Null — not read yet — is "unknown", never "none".
 */
internal fun sessionHasNoPrimary(tasks: SessionTasks?): Boolean =
    tasks != null && tasks.primaryLinkId == null && tasks.links.none { it.state == LinkState.Active && it.primary }
