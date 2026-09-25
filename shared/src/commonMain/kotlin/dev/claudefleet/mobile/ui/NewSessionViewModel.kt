package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.PastLink
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.unnamedProject
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.existingSessionId
import dev.claudefleet.mobile.net.isUnknownAction
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A machine the form can offer. An unreachable one is listed, greyed, and cannot be picked. */
data class HostChoice(val alias: String, val reachable: Boolean)

/** A project the form can offer, by the name a person recognises. */
data class ProjectChoice(val id: Long, val label: String)

data class NewSessionUiState(
    val hosts: List<HostChoice> = emptyList(),
    /** The host the session will go to: the person's pick, or the form's guess. */
    val host: String? = null,
    /** The projects matching [projectQuery], most recently used first. */
    val projects: List<ProjectChoice> = emptyList(),
    val projectQuery: String = "",
    val projectId: Long? = null,
    /** [projectId]'s name, kept even when [projectQuery] hides it from [projects]. */
    val projectLabel: String? = null,
    val newWorktree: Boolean = false,
    val branch: String = "",
    val baseBranch: String = "",
    val friendlyName: String = "",
    val creating: Boolean = false,
    val canCreate: Boolean = false,
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    val error: Friendly? = null,
    /**
     * Set in ticket mode (M8.4): the session starts work on this key through
     * `work_link start`, so the hub names the worktree after the ticket and
     * links it; the worktree and label fields are not offered, and the
     * project may be left to the hub.
     */
    val ticketKey: String? = null,
    /**
     * Ticket mode with a project picked, on a hub that starts several
     * repositories at once (claude-fleet M9.6): the other repositories this
     * ticket ran in before, newest first — *Also start in…*. Empty hides it.
     */
    val siblings: List<ProjectChoice> = emptyList(),
    /** The [siblings] ticked, in ticking order; each gets its own sibling session. */
    val siblingsTicked: List<Long> = emptyList(),
)

/**
 * The New session form: a host, a project, optionally a fresh worktree, and
 * a label.
 *
 * The tmux name is not asked for. The hub mints one when `name` is empty,
 * exactly as it does for the desktop's dialog, so the phone does not need a
 * second copy of that rule — see [dev.claudefleet.mobile.net.HubClient.newSession].
 *
 * **The host guess is derived, not stored.** The form can open before the first
 * `list_hosts` has landed, so "the host the list was filtered to", or "the only
 * reachable one", is worked out from the hosts as they stand every time, and
 * only a host the person actually tapped is remembered.
 *
 * Connection state does not gate Create. A dropped `/events` stream is not an
 * unreachable hub (see `SessionActions.ping`), and a create against a hub that
 * really is gone fails with its own explanation.
 */
class NewSessionViewModel(
    private val fleet: FleetState,
    private val actions: NewSessionActions,
    private val scope: CoroutineScope,
    /** `new_session` is not a readonly tool; a readonly pairing never gets to call it. */
    private val canWrite: Boolean,
    /** The Sessions list's host filter, if it had one — the form's first guess. */
    private val initialHost: String?,
    /** Called with the new session's id, once the hub has made it. */
    private val onCreated: (Long) -> Unit,
    /**
     * Where the `new_session` call itself runs — a scope that outlives this
     * screen. Leaving the form mid-create must not cancel the request: a
     * dropped connection can abort the hub's work half done (a worktree with
     * no session in it), which is worse than a session nobody is watching
     * being made. [onCreated] still fires; the navigator ignores it once the
     * form is no longer showing.
     */
    private val callScope: CoroutineScope = scope,
    /** Ticket mode: start work on this key instead of a plain session. */
    private val ticketKey: String? = null,
    /** How ticket mode starts work; required with [ticketKey]. */
    private val workActions: WorkActions? = null,
    /**
     * A line for the session screen about what a multi-repo start left out
     * (already running there, or failed), handed over just before
     * [onCreated]. The form closes on create, so it cannot say it itself.
     */
    private val onNote: (String) -> Unit = {},
) {
    private data class Local(
        val pickedHost: String? = null,
        val projectQuery: String = "",
        val projectId: Long? = null,
        val newWorktree: Boolean = false,
        val branch: String = "",
        val baseBranch: String = "",
        val friendlyName: String = "",
        val creating: Boolean = false,
        val error: Friendly? = null,
        /** The projects an `E_AMBIGUOUS` start offered; the list narrows to them. */
        val candidates: List<Long>? = null,
        /**
         * The hub said it cannot pick the project (`E_AMBIGUOUS`): from here
         * on the person must, or Create would send the same request and get
         * the same refusal.
         */
        val mustPickProject: Boolean = false,
        /** The links that ended on the ticket: where *Also start in…* looks for other repositories. */
        val pastLinks: List<PastLink> = emptyList(),
        /** Sibling repositories ticked, in ticking order. */
        val siblings: List<Long> = emptyList(),
    )

    private val local = MutableStateFlow(Local())

    private data class Fleet(
        val hosts: List<HostRow>,
        val projects: List<ProjectRow>,
        val status: ConnectionStatus,
        val sessions: List<SessionRow>,
    )

    val state: StateFlow<NewSessionUiState> =
        combine(
            combine(fleet.hosts, fleet.projects, fleet.status, fleet.sessions, ::Fleet),
            local,
            fleet.capabilities,
        ) { f, l, caps ->
            assemble(f.hosts, f.projects, f.status, f.sessions, l, caps)
        }.stateIn(scope, SharingStarted.Eagerly, current())

    init {
        // Where the ticket ran before, for *Also start in…*. A read; a hub
        // that cannot answer it simply offers no siblings.
        val key = ticketKey
        val work = workActions
        if (key != null && work != null && canWrite && fleet.capabilities.value.has(WORK, LINKS)) {
            scope.launch {
                val past = try {
                    work.pastLinks(key)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    emptyList()
                }
                local.update { it.copy(pastLinks = past) }
            }
        }
    }

    /** Pick a host. One the hub cannot reach is ignored — the row is greyed for that reason. */
    fun selectHost(alias: String) {
        if (fleet.hosts.value.none { it.alias == alias && it.reachable }) return
        local.update { it.copy(pickedHost = alias) }
    }

    fun onProjectQuery(query: String) {
        local.update { it.copy(projectQuery = query) }
    }

    fun selectProject(id: Long) {
        local.update { it.copy(projectId = id) }
    }

    /** Tick or untick a sibling repository for a multi-repo start. */
    fun toggleSibling(id: Long) {
        local.update { it.copy(siblings = if (id in it.siblings) it.siblings - id else it.siblings + id) }
    }

    fun setNewWorktree(on: Boolean) {
        local.update { it.copy(newWorktree = on) }
    }

    fun onBranchChange(text: String) {
        local.update { it.copy(branch = text) }
    }

    fun onBaseBranchChange(text: String) {
        local.update { it.copy(baseBranch = text) }
    }

    fun onFriendlyNameChange(text: String) {
        local.update { it.copy(friendlyName = text) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Ask the hub for the session, and hand its id to [onCreated].
     *
     * The form stays locked while the call runs — it can take minutes when the
     * hub clones the repository first — and a second tap in that time sends
     * nothing. A failure unlocks it with everything still filled in.
     */
    fun create(): Job? {
        if (ticketKey != null) return startWork(ticketKey)
        // `canCreate` is false while a create is in flight, so a second tap
        // stops here.
        val request = requestFrom(current()) ?: return null
        local.update { it.copy(creating = true, error = null) }
        return callScope.launch {
            try {
                val row = actions.newSession(request)
                local.update { it.copy(creating = false) }
                onCreated(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(creating = false, error = explainCreateFailure(t)) }
            }
        }
    }

    /**
     * Ticket mode's create: `work_link start` on the chosen host, with the
     * project only when the person picked one — otherwise the hub uses the
     * project that last worked on the key's prefix.
     *
     * Two refusals are answers rather than failures. `E_EXISTS` names the
     * session already on the ticket, and the phone opens it (Jump — never a
     * second session). `E_AMBIGUOUS` says the hub cannot pick a project and
     * lists candidates; the project list narrows to them.
     */
    private fun startWork(key: String): Job? {
        val s = current()
        val actions = workActions ?: return null
        if (!s.canCreate) return null
        val host = s.host ?: return null
        val primary = s.projectId
        if (primary != null && s.siblingsTicked.isNotEmpty()) return startMany(key, host, listOf(primary) + s.siblingsTicked, actions)
        local.update { it.copy(creating = true, error = null) }
        return callScope.launch {
            try {
                val row = actions.start(key, host, s.projectId)
                local.update { it.copy(creating = false) }
                onCreated(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val tool = t as? HubError.Tool
                val jump = tool?.existingSessionId()
                when {
                    jump != null -> {
                        local.update { it.copy(creating = false) }
                        onCreated(jump)
                    }
                    tool?.code == "E_AMBIGUOUS" -> {
                        val ids = projectCandidates(tool)
                        local.update {
                            it.copy(
                                creating = false,
                                candidates = ids.ifEmpty { null },
                                mustPickProject = true,
                                error = friendlyWork(t),
                            )
                        }
                    }
                    else -> {
                        if (tool != null && tool.isUnknownAction()) fleet.actionMissing(WORK_LINK, START)
                        local.update { it.copy(creating = false, error = explainCreateFailure(t)) }
                    }
                }
            }
        }
    }

    /**
     * A multi-repo start: one `work_link start { project_ids }` for the
     * picked project and the ticked siblings. The answer is a report — the
     * picked project's session opens (else the first that started); what
     * was already running or failed is handed on as a note. When nothing
     * started but the key already runs somewhere, that session opens, as a
     * single start's `E_EXISTS` does.
     */
    private fun startMany(key: String, host: String, ids: List<Long>, actions: WorkActions): Job {
        local.update { it.copy(creating = true, error = null) }
        return callScope.launch {
            try {
                val r = actions.startMulti(key, host, ids)
                val label = { id: Long -> fleet.projects.value.firstOrNull { it.id == id }?.label ?: unnamedProject(id) }
                val note = multiStartNote(r, label)
                val open = r.started.firstOrNull { it.projectId == ids.first() }?.id
                    ?: r.started.firstOrNull()?.id
                    ?: r.skipped.firstNotNullOfOrNull { it.sessionId }
                local.update { it.copy(creating = false) }
                if (open != null) {
                    note?.let(onNote)
                    onCreated(open)
                } else {
                    local.update { it.copy(error = Friendly("Nothing started", note ?: "The hub started no session.", isError = true)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(creating = false, error = explainCreateFailure(t)) }
            }
        }
    }

    private fun current(): NewSessionUiState =
        assemble(
            fleet.hosts.value,
            fleet.projects.value,
            fleet.status.value,
            fleet.sessions.value,
            local.value,
            fleet.capabilities.value,
        )

    private fun assemble(
        hosts: List<HostRow>,
        projects: List<ProjectRow>,
        status: ConnectionStatus,
        sessions: List<SessionRow>,
        l: Local,
        caps: HubCapabilities,
    ): NewSessionUiState {
        // Hidden is the desktop's "do not show me this". The exception is the
        // host the list was filtered to: the person tapped + on that host's own
        // list, and a form without it would contradict the screen behind it.
        val offered = hosts
            .filter { !it.hidden || it.alias == initialHost }
            .sortedWith(BY_ALIAS)
        val reachable = offered.filter { it.reachable }.map { it.alias }
        val host = when {
            l.pickedHost != null && l.pickedHost in reachable -> l.pickedHost
            l.pickedHost != null -> null
            initialHost != null -> initialHost.takeIf { it in reachable }
            else -> reachable.singleOrNull()
        }

        val query = l.projectQuery.trim()
        val chosen = projects.firstOrNull { it.id == l.projectId }
        val listed = projects
            .sortedWith(MOST_RECENT_FIRST)
            .filter { l.candidates == null || it.id in l.candidates }
            .filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
            .map { ProjectChoice(it.id, it.label) }

        val branchOk = !l.newWorktree || isBranchName(l.branch.trim())
        // *Also start in…*: only with a project picked (the siblings are the
        // other repositories), on a hub whose `work_link` takes
        // `project_ids` — an older one would ignore it and start one.
        val siblings = if (ticketKey != null && chosen != null && canWrite && caps.hasParam(WORK_LINK, PROJECT_IDS)) {
            siblingCandidates(ticketKey, chosen.id, l.pastLinks, sessions, projects)
        } else {
            emptyList()
        }
        // A tick left over from another pick, or a candidate since gone,
        // never reaches the hub: only what the form shows is started.
        val ticked = l.siblings.filter { id -> siblings.any { it.id == id } }.take(MULTI_START_MAX - 1)
        val ready = if (ticketKey != null) {
            // The project is the hub's to pick unless the person chose one —
            // or the hub already said it cannot.
            workActions != null && caps.has(WORK_LINK, START) && (!l.mustPickProject || chosen != null)
        } else {
            chosen != null && branchOk
        }
        return NewSessionUiState(
            hosts = offered.map { HostChoice(it.alias, it.reachable) },
            host = host,
            projects = listed,
            projectQuery = l.projectQuery,
            projectId = l.projectId,
            projectLabel = chosen?.label,
            newWorktree = l.newWorktree,
            branch = l.branch,
            baseBranch = l.baseBranch,
            friendlyName = l.friendlyName,
            creating = l.creating,
            canCreate = canWrite && !l.creating && host != null && ready,
            status = status,
            error = l.error,
            ticketKey = ticketKey,
            siblings = siblings,
            siblingsTicked = ticked,
        )
    }

    private fun requestFrom(s: NewSessionUiState): NewSessionRequest? {
        if (!s.canCreate) return null
        return NewSessionRequest(
            hostAlias = s.host ?: return null,
            projectId = s.projectId ?: return null,
            newWorktree = s.branch.trim().takeIf { s.newWorktree },
            baseBranch = s.baseBranch.trim().takeIf { s.newWorktree && it.isNotEmpty() },
            friendlyName = s.friendlyName.trim().ifEmpty { null },
        )
    }
}

private const val START = "start"
private const val LINKS = "links"
private const val PROJECT_IDS = "project_ids"

/** The hub's cap on one multi-repo start (`MULTI_START_MAX`): the picked project and seven siblings. */
internal const val MULTI_START_MAX = 8

/**
 * The repositories [key] ran in before, other than [primary]: ended links'
 * projects and live sessions on the key, newest first, only projects the
 * fleet still lists. The desktop's `siblingCandidates`.
 */
internal fun siblingCandidates(
    key: String,
    primary: Long,
    past: List<PastLink>,
    sessions: List<SessionRow>,
    projects: List<ProjectRow>,
): List<ProjectChoice> {
    val newest = LinkedHashMap<Long, Long>()
    fun add(id: Long?, at: Long) {
        if (id == null || id == primary) return
        newest[id] = maxOf(newest[id] ?: Long.MIN_VALUE, at)
    }
    for (l in past) add(l.snapProjectId, l.endedAt ?: l.createdAt)
    for (s in sessions) if (s.work?.key.equals(key, ignoreCase = true)) add(s.projectId, s.lastActivityAt ?: 0)
    val byId = projects.associateBy { it.id }
    return newest.entries
        .sortedByDescending { it.value }
        .mapNotNull { (id, _) -> byId[id]?.let { ProjectChoice(it.id, it.label) } }
}

/** One line on what a multi-repo start left out, or null when nothing — the desktop's `multiStartNote`. */
internal fun multiStartNote(r: MultiStart, labelOf: (Long) -> String): String? {
    val parts = r.skipped.map { "${labelOf(it.projectId)}: already running" } +
        r.failed.map { "${labelOf(it.projectId)}: ${it.message}" }
    return if (parts.isEmpty()) null else "Started ${r.started.size}; ${parts.joinToString("; ")}"
}

/** The project ids an `E_AMBIGUOUS` start offers — `{"candidates": [{"id", "owner", "repo"}]}`. */
private fun projectCandidates(e: HubError.Tool): List<Long> =
    ((e.details as? JsonObject)?.get("candidates") as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull }

/**
 * Enough of a branch name to be worth sending: something, with no whitespace.
 * The hub validates the rest and says what it did not like.
 */
private fun isBranchName(name: String): Boolean = name.isNotEmpty() && name.none { it.isWhitespace() }

/**
 * [friendly], except that a connection lost mid-create is not a plain failure.
 * The call's own deadline sits above the hub's, so losing it means the
 * connection went, not that the hub gave up — the session may well exist, and
 * "try again" would make a second one.
 */
private fun explainCreateFailure(t: Throwable): Friendly {
    val base = friendly(t)
    return if (t is HubError.Transport) {
        base.copy(body = "The session may still have been created — check the list before trying again.")
    } else {
        base
    }
}

/** Most recently used first; never-used projects after, by name. */
private val MOST_RECENT_FIRST: Comparator<ProjectRow> =
    compareByDescending<ProjectRow> { it.lastSessionAt ?: Long.MIN_VALUE }.thenBy { it.label }
