package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.knownKeyPrefixes
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.model.orgOf
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.FORCE_CROSS_ORG
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    /**
     * Tickets the add sheet offers, narrowed by [addQuery]; ones already
     * linked are left out, and ones of another organisation come last.
     */
    val addCandidates: List<Ticket> = emptyList(),
    /** The project keys ("FLEET") of tickets the phone has seen, for the typed key's "Did you mean". */
    val keyPrefixes: List<String> = emptyList(),
    /**
     * Ticket id → the name of its organisation, for a candidate known to
     * belong to a different organisation than this session. The hub refuses
     * that link and the phone never overrides it (decision D15), so such a
     * row is drawn as not linkable here.
     */
    val otherOrg: Map<Long, String> = emptyMap(),
    /** This session's organisation, when the phone knows it. */
    val sessionOrgName: String? = null,
    /** A link across organisations waiting on the person's choice: share it, or move the session. */
    val crossOrg: CrossOrgChoice? = null,
    val busy: Boolean = false,
    val error: Friendly? = null,
    /** The error is a write another device beat: *Reload* is offered. */
    val conflict: Boolean = false,
) {
    /** What the chip counts: the tasks this session is on, or may be on. */
    val count: Int get() = active.size + suggested.size
}

/**
 * A task and a session in different organisations (the hub's org rule,
 * claude-fleet M5) — and the two ways on, for the person to pick.
 */
data class CrossOrgChoice(
    /** What was being linked: a key, or what was typed. */
    val what: String,
    val taskOrgName: String?,
    val sessionOrgName: String?,
    /**
     * **Link anyway**: share the task across organisations. A full token on
     * a hub whose `work_link` takes `force_cross_org`, while connected.
     */
    val canShare: Boolean,
    /**
     * **Move this session** into the task's organisation: the hub command
     * that does it — an org rule is the master's (`work_admin`), never a
     * paired client's, so the phone hands it over to copy. Null when the
     * task's organisation is not known.
     */
    val moveCommand: String? = null,
    /** What [moveCommand] moves, in words. */
    val moveCovers: String? = null,
)

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
        val crossOrg: CrossOrgPending? = null,
    )

    /** A refused (or known-to-be-refused) link, kept so **Link anyway** can send it again. */
    private data class CrossOrgPending(
        val itemId: Long?,
        val key: String?,
        val what: String,
        val taskOrg: Long?,
        val sessionOrg: Long?,
    )

    private val local = MutableStateFlow(Local())

    /** The org directory and this session's org in it — what the add list reads to mark another org's tickets. */
    private data class Orgs(val directory: OrgDirectory, val session: SessionRow?, val project: ProjectRow?) {
        val sessionOrg: Long? get() = session?.orgOf
    }

    private fun orgsOf(directory: OrgDirectory, rows: List<SessionRow>, projects: List<ProjectRow>): Orgs {
        val row = rows.firstOrNull { it.id == sessionId }
        return Orgs(directory, row, row?.projectId?.let { id -> projects.firstOrNull { it.id == id } })
    }

    private val orgs: Flow<Orgs> =
        combine(fleet.orgs, fleet.sessions, fleet.projects, ::orgsOf).distinctUntilChanged()

    val state: StateFlow<SessionTasksUiState> =
        combine(fleet.capabilities, fleet.status, fleet.tickets, orgs, local) { caps, status, cache, o, l -> assemble(caps, status, cache, o, l) }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                assemble(fleet.capabilities.value, fleet.status.value, fleet.tickets.value, orgsOf(fleet.orgs.value, fleet.sessions.value, fleet.projects.value), local.value),
            )

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

    /** Back out of the cross-org choice; nothing is sent. */
    fun dismissCrossOrg() {
        local.update { it.copy(crossOrg = null) }
    }

    /**
     * **Link anyway**: the link the hub refused across organisations, sent
     * again with `force_cross_org` — only from the choice the person saw.
     */
    fun shareAcrossOrgs(): Job? {
        val pending = local.value.crossOrg ?: return null
        if (state.value.crossOrg?.canShare != true) return null
        local.update { it.copy(crossOrg = null) }
        return write(LINK) {
            actions.link(sessionId, itemId = pending.itemId, key = pending.key, primary = takesPrimary(), shareAcrossOrgs = true)
            local.update { it.copy(addOpen = false, addQuery = "") }
        }
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

    /**
     * Link a ticket from the list. One the phone already knows belongs to
     * another organisation is not sent: the hub would refuse it, so the
     * choice opens instead — share it anyway, or move the session.
     */
    fun add(ticket: Ticket): Job? {
        if (!state.value.canAdd) return null
        val orgs = currentOrgs()
        val taskOrg = orgs.directory.orgOf(ticket)
        if (taskOrg != null && orgs.sessionOrg != null && taskOrg != orgs.sessionOrg) {
            local.update { it.copy(crossOrg = CrossOrgPending(ticket.id, null, ticket.label, taskOrg, orgs.sessionOrg), error = null, conflict = false) }
            return null
        }
        return write(LINK, crossOrg = { t -> pendingFrom(t, itemId = ticket.id, key = null, what = ticket.label) }) {
            actions.link(sessionId, itemId = ticket.id, primary = takesPrimary())
            local.update { it.copy(addOpen = false, addQuery = "") }
        }
    }

    /**
     * Link what was typed: a key as a bare key, a pasted ticket URL only by
     * the ticket it resolves to (the hub would otherwise take the URL as a
     * free-form key and name a group after it).
     */
    fun addTyped(): Job? {
        val reference = local.value.addQuery.trim()
        if (reference.isEmpty()) return null
        // The item a pasted link resolved to, for **Link anyway**.
        var resolved: Long? = null
        return write(
            LINK,
            crossOrg = { t -> pendingFrom(t, itemId = resolved, key = reference.takeIf { resolved == null }, what = reference) },
        ) {
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
                resolved = ticket.id
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

    /**
     * [crossOrg] turns the hub's cross-org refusal of this write into the
     * choice (a link); without it the refusal is said in words.
     */
    private fun write(action: String, crossOrg: ((HubError.Tool) -> CrossOrgPending)? = null, call: suspend () -> Unit): Job? {
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
                if (t is HubError.Tool && t.isCrossOrg() && crossOrg != null) {
                    local.update { it.copy(crossOrg = crossOrg(t), error = null, conflict = false) }
                } else {
                    val error = if (t is HubError.Tool && t.isCrossOrg()) crossOrgLinkRefusal(t) else friendlyWorkWrite(t)
                    local.update { it.copy(error = error, conflict = t.isConflict()) }
                }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    /** A cross-org refusal of a write that has no choice (a suggestion's confirm), in words. */
    private fun crossOrgLinkRefusal(t: HubError.Tool): Friendly {
        val names = fleet.orgs.value
        return crossOrgLinkWords("This task", t.orgField("work_org_id")?.let(names::name), t.orgField("session_org_id")?.let(names::name), explain(t))
    }

    /** The hub's refusal, kept with what to send again: its details name both orgs. */
    private fun pendingFrom(t: HubError.Tool, itemId: Long?, key: String?, what: String) =
        CrossOrgPending(itemId, key, what, t.orgField("work_org_id"), t.orgField("session_org_id") ?: currentOrgs().sessionOrg)

    private fun currentOrgs() = orgsOf(fleet.orgs.value, fleet.sessions.value, fleet.projects.value)

    private fun allowed(caps: HubCapabilities, status: ConnectionStatus, action: String): Boolean =
        canWrite && status.isConnected() && caps.has(WORK_LINK, action)

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, cache: List<Ticket>, o: Orgs, l: Local): SessionTasksUiState {
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
        // Another org only when both sides are known and differ — the hub's
        // own rule (`check_cross_org`): unassigned on either side links.
        fun otherOrgOf(t: Ticket): Long? = o.directory.orgOf(t)?.takeIf { o.sessionOrg != null && it != o.sessionOrg }
        val candidates = (l.offered + cache).distinctBy { it.id }
            .filter { it.id !in linkedItems }
            .filter { t -> query.isEmpty() || t.label.contains(query, ignoreCase = true) || t.title.contains(query, ignoreCase = true) }
            .sortedBy { otherOrgOf(it) != null }
            .take(MAX_CANDIDATES)
        val otherOrg = candidates.mapNotNull { t -> otherOrgOf(t)?.let { t.id to o.directory.name(it) } }.toMap()
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
            keyPrefixes = if (l.addOpen) knownKeyPrefixes(l.offered + cache) else emptyList(),
            otherOrg = otherOrg,
            sessionOrgName = o.sessionOrg?.let(o.directory::name),
            crossOrg = l.crossOrg?.let { p ->
                val move = p.taskOrg?.let { moveSessionCommand(it, o.project, o.session?.hostAlias) }
                CrossOrgChoice(
                    what = p.what,
                    taskOrgName = p.taskOrg?.let(o.directory::name),
                    sessionOrgName = p.sessionOrg?.let(o.directory::name),
                    canShare = allowed(caps, status, LINK) && caps.accepts(WORK_LINK, FORCE_CROSS_ORG),
                    moveCommand = move?.first,
                    moveCovers = move?.second,
                )
            },
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

/** An org id from a cross-org refusal's `details`. */
private fun HubError.Tool.orgField(field: String): Long? =
    ((details as? JsonObject)?.get(field) as? JsonPrimitive)?.longOrNull

/**
 * The hub command that puts this session in [orgId], and what else it moves
 * with it: an org rule for the session's repository when it has a real one
 * (`local` is an adopted folder's placeholder owner, never an owner), else
 * one for its host. Null without either.
 */
internal fun moveSessionCommand(orgId: Long, project: ProjectRow?, hostAlias: String?): Pair<String, String>? {
    val owner = project?.owner?.trim().orEmpty()
    val repo = project?.repo?.trim().orEmpty()
    return when {
        owner.isNotEmpty() && owner != "local" && repo.isNotEmpty() ->
            "fleet-hub org rule add $orgId --owner ${shellWord(owner)} --repo ${shellWord(repo)}" to
                "every session in $owner/$repo"
        !hostAlias.isNullOrBlank() ->
            "fleet-hub org rule add $orgId --host ${shellWord(hostAlias)}" to "every session on $hostAlias"
        else -> null
    }
}

/** A word as a shell reads it back: bare when plain, else single-quoted. */
private fun shellWord(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/@:" }) s else "'" + s.replace("'", "'\\''") + "'"

/**
 * A link across organisations that has no choice to offer (a suggestion's
 * confirm), in words: what happened and how to put the session in the
 * task's organisation.
 */
internal fun crossOrgLinkWords(what: String, taskOrg: String?, sessionOrg: String?, details: String?): Friendly {
    val task = taskOrg?.let { "$what belongs to $it" } ?: "$what belongs to another organisation"
    val session = sessionOrg?.let { "this session to $it" } ?: "this session to a different one"
    return Friendly(
        "Another organisation",
        "Not linked: $task and $session. The phone does not link work across organisations. " +
            "If the session is in the wrong organisation, add an org rule for its repository or host " +
            "(fleet-hub org rule add, or the desktop's Settings → Work → Organisations).",
        isError = true,
        details = details,
    )
}

/**
 * The session's links are read ([tasks] non-null) and none of them is its
 * primary. Null — not read yet — is "unknown", never "none".
 */
internal fun sessionHasNoPrimary(tasks: SessionTasks?): Boolean =
    tasks != null && tasks.primaryLinkId == null && tasks.links.none { it.state == LinkState.Active && it.primary }
