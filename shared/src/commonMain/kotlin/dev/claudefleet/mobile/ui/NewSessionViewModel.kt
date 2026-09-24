package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.net.HubError
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
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<NewSessionUiState> =
        combine(fleet.hosts, fleet.projects, fleet.status, local) { hosts, projects, status, l ->
            assemble(hosts, projects, status, l)
        }.stateIn(scope, SharingStarted.Eagerly, current())

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

    private fun current(): NewSessionUiState =
        assemble(fleet.hosts.value, fleet.projects.value, fleet.status.value, local.value)

    private fun assemble(
        hosts: List<HostRow>,
        projects: List<ProjectRow>,
        status: ConnectionStatus,
        l: Local,
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
            .filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) }
            .map { ProjectChoice(it.id, it.label) }

        val branchOk = !l.newWorktree || isBranchName(l.branch.trim())
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
            canCreate = canWrite && !l.creating && host != null && chosen != null && branchOk,
            status = status,
            error = l.error,
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
