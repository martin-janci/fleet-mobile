package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.StartSkip
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubCapabilities
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
    /** The existing worktree picked to start in, if any. */
    val worktreeId: Long? = null,
    /** A background agent may be started from here (`new_bg_session`, a pairing that may write, not ticket mode). */
    val backgroundAvailable: Boolean = false,
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
    /**
     * Why Create is off, in words, while the person can fix it: a greyed
     * button with no reason left them guessing which field was wrong.
     */
    val missing: String? = null,
    /** The branch typed is not one git would take. */
    val branchInvalid: Boolean = false,
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
     * Multi-start (work graph M13.4d, decision D15): ticket mode, a full
     * token, and a hub whose `work_link` takes `project_ids`. The extra
     * projects are offered once a first project is picked.
     */
    val canMultiStart: Boolean = false,
    /** The projects that may join the start — every other one, most recent first. */
    val alsoIn: List<ProjectChoice> = emptyList(),
    /** The ticked ones, in ticking order; at most [ALSO_IN_MAX]. */
    val alsoInIds: List<Long> = emptyList(),
    /** The ticket's organisation, when the phone knows it — shown beside the picker and on the confirm sheet. */
    val orgLabel: String? = null,
    /** The confirm sheet, while it is up: nothing is sent until it is confirmed. */
    val confirm: MultiStartConfirm? = null,
    /** What the last multi-start answered, project by project. */
    val result: MultiStartResult? = null,
)

/** The confirm sheet: what will start, how many, and in which organisation. */
data class MultiStartConfirm(
    val key: String,
    val host: String,
    /** `owner/repo` of each project, the first-picked first. */
    val projects: List<String>,
    /** The ticket's organisation; null when the phone does not know it. */
    val orgLabel: String?,
) {
    val count: Int get() = projects.size
}

/** How one project of a multi-start ended. */
enum class StartOutcome { STARTED, STARTED_WITH_WARNING, ALREADY_RUNNING, OUT_OF_TIME, CROSS_ORG, REFUSED }

/** One project's line in the result: what happened there, in words, and the session to open when there is one. */
data class ProjectResult(
    val projectId: Long,
    val project: String,
    val outcome: StartOutcome,
    val text: String,
    val sessionId: Long? = null,
)

data class MultiStartResult(val key: String, val projects: List<ProjectResult>) {
    val startedCount: Int get() = projects.count { it.outcome == StartOutcome.STARTED || it.outcome == StartOutcome.STARTED_WITH_WARNING }
}

/** The hub's cap on one multi-start (`MULTI_START_MAX`), the first project included. */
const val MULTI_START_MAX = 8

/** How many projects may be ticked beside the first. */
const val ALSO_IN_MAX = MULTI_START_MAX - 1

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
) {
    private data class Local(
        val pickedHost: String? = null,
        val projectQuery: String = "",
        val projectId: Long? = null,
        val newWorktree: Boolean = false,
        val branch: String = "",
        val baseBranch: String = "",
        val friendlyName: String = "",
        /** An existing worktree of the project on the host to start in (from the worktree list). */
        val worktreeId: Long? = null,
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
        /** Multi-start: the extra projects ticked, in ticking order. */
        val alsoIn: List<Long> = emptyList(),
        val confirming: Boolean = false,
        val result: MultiStartResult? = null,
    )

    /** What ticket mode reads beyond the form: the hub's gates, and the ticket's org. */
    private data class WorkView(
        val caps: HubCapabilities,
        val tickets: List<Ticket>,
        val orgs: OrgDirectory,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<NewSessionUiState> =
        combine(
            fleet.hosts,
            fleet.projects,
            fleet.status,
            local,
            combine(fleet.capabilities, fleet.tickets, fleet.orgs, ::WorkView),
        ) { hosts, projects, status, l, work ->
            assemble(hosts, projects, status, l, work)
        }.stateIn(scope, SharingStarted.Eagerly, current())

    /** Pick a host. One the hub cannot reach is ignored — the row is greyed for that reason. */
    fun selectHost(alias: String) {
        if (fleet.hosts.value.none { it.alias == alias && it.reachable }) return
        local.update { it.copy(pickedHost = alias, worktreeId = null) }
    }

    fun onProjectQuery(query: String) {
        local.update { it.copy(projectQuery = query) }
    }

    fun selectProject(id: Long) {
        local.update { it.copy(projectId = id, worktreeId = null) }
    }

    /** Start in an existing worktree of the project (null: its main checkout); a new branch's worktree is off then. */
    fun selectWorktree(id: Long?) {
        local.update { it.copy(worktreeId = id, newWorktree = if (id != null) false else it.newWorktree) }
    }

    fun setNewWorktree(on: Boolean) {
        local.update { it.copy(newWorktree = on, worktreeId = if (on) null else it.worktreeId) }
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
     * Tick or untick a project to start in beside the first. Past
     * [ALSO_IN_MAX] a tick is ignored — the hub would refuse the whole start.
     */
    fun toggleAlsoIn(id: Long) {
        if (!current().canMultiStart) return
        local.update { l ->
            when {
                id in l.alsoIn -> l.copy(alsoIn = l.alsoIn - id)
                l.alsoIn.size >= ALSO_IN_MAX -> l
                else -> l.copy(alsoIn = l.alsoIn + id)
            }
        }
    }

    /** Back out of the confirm sheet; nothing was sent. */
    fun cancelMultiStart() {
        local.update { it.copy(confirming = false) }
    }

    /**
     * The confirm sheet's **Start**: one `work_link start { project_ids }`
     * for the first project and every ticked one.
     *
     * Everything started cleanly → the first session opens, as a single
     * start's does. Anything else — a project already running, one refused,
     * one out of time — stays on the form as a line per project, because
     * leaving would hide exactly what went wrong. A cross-org refusal is said
     * in words and is final: the phone never sends the start again with
     * `force_cross_org` (decision D15).
     */
    fun confirmMultiStart(): Job? {
        val s = current()
        val confirm = s.confirm ?: return null
        val actions = workActions ?: return null
        val first = s.projectId ?: return null
        if (!s.canCreate || !s.canMultiStart) return null
        val ids = listOf(first) + s.alsoInIds
        val labels = fleet.projects.value.associate { it.id to it.label }
        local.update { it.copy(confirming = false, creating = true, error = null, result = null) }
        return callScope.launch {
            try {
                val answer = actions.startMany(confirm.key, confirm.host, ids)
                val result = multiStartResult(confirm.key, answer, ids, labels, confirm.orgLabel)
                val clean = result.projects.all { it.outcome == StartOutcome.STARTED }
                local.update { it.copy(creating = false, result = result.takeUnless { clean }) }
                if (clean) result.projects.firstOrNull()?.sessionId?.let(onCreated)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val tool = t as? HubError.Tool
                if (tool != null && tool.isUnknownAction()) fleet.actionMissing(WORK_LINK, START)
                val error = if (tool != null && tool.isCrossOrg()) crossOrgRefusal(confirm.key, confirm.orgLabel, t) else explainCreateFailure(t)
                local.update { it.copy(creating = false, error = error) }
            }
        }
    }

    /** Open one session the multi-start made. */
    fun openStarted(sessionId: Long) {
        local.update { it.copy(result = null) }
        onCreated(sessionId)
    }

    fun dismissResult() {
        local.update { it.copy(result = null) }
    }

    /**
     * Ask the hub for the session, and hand its id to [onCreated].
     *
     * The form stays locked while the call runs — it can take minutes when the
     * hub clones the repository first — and a second tap in that time sends
     * nothing. A failure unlocks it with everything still filled in.
     */
    fun create(): Job? {
        if (ticketKey != null) {
            val s = current()
            // Ticked projects: a multi-start, which is confirmed first.
            if (s.canMultiStart && s.alsoInIds.isNotEmpty()) {
                if (s.canCreate) local.update { it.copy(confirming = true, error = null) }
                return null
            }
            return startWork(ticketKey)
        }
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
     * A background agent on the chosen host instead: headless, supervised,
     * started on [prompt] (`new_bg_session`). Its fleet row arrives with the
     * next reconcile when the hub could not match it at once; then
     * [onUntracked] says so rather than [onCreated] opening nothing.
     */
    fun startBackground(name: String, prompt: String, onUntracked: (String?) -> Unit): Job? {
        val s = current()
        val host = s.host ?: return null
        if (!s.backgroundAvailable || s.creating || prompt.isBlank()) return null
        local.update { it.copy(creating = true, error = null) }
        return callScope.launch {
            try {
                val result = actions.newBackground(host, name.trim().ifEmpty { prompt.trim().take(40) }, prompt.trim())
                local.update { it.copy(creating = false) }
                val row = result.session
                if (row != null) onCreated(row.id) else onUntracked(result.warning)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(creating = false, error = friendly(t)) }
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

    private fun current(): NewSessionUiState =
        assemble(
            fleet.hosts.value,
            fleet.projects.value,
            fleet.status.value,
            local.value,
            WorkView(fleet.capabilities.value, fleet.tickets.value, fleet.orgs.value),
        )

    private fun assemble(
        hosts: List<HostRow>,
        projects: List<ProjectRow>,
        status: ConnectionStatus,
        l: Local,
        work: WorkView,
    ): NewSessionUiState {
        val caps = work.caps
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
        val ready = if (ticketKey != null) {
            // The project is the hub's to pick unless the person chose one —
            // or the hub already said it cannot.
            workActions != null && caps.has(WORK_LINK, START) && (!l.mustPickProject || chosen != null)
        } else {
            chosen != null && branchOk
        }
        // Multi-start needs the first project named: `project_ids` is every
        // repository, and the hub picks none of them.
        val canMultiStart = ticketKey != null && canWrite && workActions != null &&
            caps.has(WORK_LINK, START) && caps.accepts(WORK_LINK, PROJECT_IDS)
        val others = if (canMultiStart && chosen != null) {
            projects.filter { it.id != chosen.id }.sortedWith(MOST_RECENT_FIRST)
        } else {
            emptyList()
        }
        // A tick counts only while its project is still offered: never a
        // start in a repository the form does not show.
        val ticked = l.alsoIn.filter { id -> others.any { it.id == id } }
        val alsoIn = others
            .filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.id in ticked }
            .map { ProjectChoice(it.id, it.label) }
        val orgLabel = ticketKey?.let { key ->
            work.tickets.firstOrNull { it.key == key }?.let(work.orgs::orgOf)?.let(work.orgs::name)
        }
        val canCreate = canWrite && !l.creating && host != null && ready
        val branchInvalid = l.newWorktree && l.branch.isNotBlank() && !branchOk
        val missing = when {
            !canWrite || l.creating || canCreate -> null
            host == null -> if (reachable.isEmpty()) "No host is reachable right now." else "Pick a host."
            ticketKey == null && chosen == null -> "Pick a project."
            ticketKey != null && l.mustPickProject && chosen == null -> "Pick a project — the hub could not choose one."
            ticketKey == null && !branchOk -> if (l.branch.isBlank()) "Name the new branch." else "A branch name has no spaces."
            else -> null
        }
        val byId = projects.associateBy { it.id }
        val confirm = if (l.confirming && canCreate && host != null && ticketKey != null && chosen != null && ticked.isNotEmpty()) {
            MultiStartConfirm(
                key = ticketKey,
                host = host,
                projects = (listOf(chosen.id) + ticked).map { byId[it]?.label ?: unnamedLabel(it) },
                orgLabel = orgLabel,
            )
        } else {
            null
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
            canCreate = canCreate,
            missing = missing,
            branchInvalid = branchInvalid,
            status = status,
            error = l.error,
            ticketKey = ticketKey,
            canMultiStart = canMultiStart,
            alsoIn = alsoIn,
            alsoInIds = ticked,
            orgLabel = orgLabel,
            confirm = confirm,
            result = l.result,
            backgroundAvailable = canWrite && ticketKey == null && caps.newBgSession,
            worktreeId = l.worktreeId,
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
            worktreeId = s.worktreeId.takeIf { !s.newWorktree },
        )
    }
}

private const val START = "start"
private const val PROJECT_IDS = "project_ids"

private fun unnamedLabel(id: Long) = ProjectRow(id).label

/** The org rule's refusal: `details.cross_org`, as the hub's `check_cross_org` sets it. */
internal fun HubError.Tool.isCrossOrg(): Boolean =
    ((details as? JsonObject)?.get("cross_org") as? JsonPrimitive)?.content == "true"

/**
 * A cross-org refusal, in words. It names what happened and where it can be
 * done instead — and deliberately not the hub's own hint to pass
 * `force_cross_org`, which the phone never does (decision D15).
 */
internal fun crossOrgWords(key: String, orgLabel: String?): String =
    "Not started: this project belongs to a different organisation than $key" +
        (orgLabel?.let { " ($it)" } ?: "") +
        ". The phone does not start work across organisations; do it from the desktop if it is meant."

private fun crossOrgRefusal(key: String, orgLabel: String?, t: HubError.Tool): Friendly =
    Friendly("Another organisation", crossOrgWords(key, orgLabel), isError = true, details = explain(t))

/**
 * One line per project the person asked for, in the order they asked: the
 * hub's four lists folded back onto the projects. A project the answer does
 * not mention says so rather than vanishing.
 */
internal fun multiStartResult(
    key: String,
    answer: MultiStart,
    asked: List<Long>,
    labels: Map<Long, String>,
    orgLabel: String?,
): MultiStartResult {
    fun label(id: Long) = labels[id] ?: unnamedLabel(id)
    val warned = answer.warnings.associateBy { it.projectId }
    val lines = mutableMapOf<Long, ProjectResult>()
    for (row in answer.started) {
        val id = row.projectId ?: continue
        val warning = warned[id]
        lines[id] = if (warning == null) {
            ProjectResult(id, label(id), StartOutcome.STARTED, "Started", row.id)
        } else {
            ProjectResult(id, label(id), StartOutcome.STARTED_WITH_WARNING, "Started, but ${warning.message}", row.id)
        }
    }
    for (skip in answer.skipped) {
        val id = skip.projectId
        lines[id] = if (skip.reason == StartSkip.SKIP_DEADLINE) {
            ProjectResult(id, label(id), StartOutcome.OUT_OF_TIME, "Not started: the hub ran out of time before this one. Start again to add it.")
        } else {
            ProjectResult(id, label(id), StartOutcome.ALREADY_RUNNING, "Already running there — not started again.", skip.sessionId)
        }
    }
    for (f in answer.failed) {
        val id = f.projectId
        lines[id] = if (f.crossOrg) {
            ProjectResult(id, label(id), StartOutcome.CROSS_ORG, crossOrgWords(key, orgLabel))
        } else {
            ProjectResult(id, label(id), StartOutcome.REFUSED, "Not started: ${f.message}")
        }
    }
    val ordered = asked.distinct().map { id ->
        lines.remove(id) ?: ProjectResult(id, label(id), StartOutcome.REFUSED, "Not started: the hub did not say what happened here.")
    }
    // A session the hub made somewhere nobody asked is still shown, not lost.
    return MultiStartResult(key, ordered + lines.values)
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
