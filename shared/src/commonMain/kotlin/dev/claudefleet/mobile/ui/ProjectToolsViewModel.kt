package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ProjectActions
import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.model.HostWorktrees
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A GitHub creation the hub wants confirmed: what, and the token to send back. */
data class PendingCreate(val owner: String, val repo: String, val token: String)

data class ProjectToolsUiState(
    val canAdd: Boolean = false,
    val canListGithub: Boolean = false,
    val canSeeWorktrees: Boolean = false,
    val canDeleteWorktree: Boolean = false,
    /** The Add project sheet is open, for this host. */
    val addingOn: String? = null,
    val repos: List<GithubRepo>? = null,
    val adding: Boolean = false,
    /** What [adding] is adding, and where: the New layout's Cloning screen says it, and so does the form once that screen is left. */
    val addingWhat: String? = null,
    val addingWhere: String? = null,
    val pendingCreate: PendingCreate? = null,
    /** The worktrees of the form's host and project, once read. */
    val worktrees: HostWorktrees? = null,
    val deleting: Long? = null,
    val error: Friendly? = null,
)

/**
 * The New session form's project tools — the desktop's Add project dialog
 * and its worktree list: clone a repository onto a host (a URL, or one `gh`
 * there can see), add a checkout already on the hub's own host, or make a new one (on GitHub too, which the hub asks to
 * have confirmed: [PendingCreate]); and the chosen project's worktrees on
 * the chosen host, each deletable when no session lives in it.
 */
class ProjectToolsViewModel(
    private val fleet: FleetState,
    private val actions: ProjectActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private val local = MutableStateFlow(ProjectToolsUiState())

    val state: StateFlow<ProjectToolsUiState> = combine(local, fleet.capabilities) { l, caps ->
        l.copy(
            canAdd = canWrite && caps.addProject,
            canListGithub = caps.githubRepos,
            canSeeWorktrees = caps.hostWorktrees,
            canDeleteWorktree = canWrite && caps.deleteWorktree,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ProjectToolsUiState())

    fun openAdd(hostAlias: String): Job = scope.launch {
        local.update { it.copy(addingOn = hostAlias, repos = null, error = null, pendingCreate = null) }
        if (!fleet.capabilities.value.githubRepos) return@launch
        guarded {
            val repos = actions.githubRepos(hostAlias)
            local.update { if (it.addingOn == hostAlias) it.copy(repos = repos) else it }
        }
    }

    fun closeAdd() {
        local.update { it.copy(addingOn = null, pendingCreate = null) }
    }

    /**
     * Move the open Add project to [hostAlias] (the New layout's Where step).
     * The repositories `gh` listed stay: a GitHub URL clones on any host
     * whose `gh` can read it, and the hub says so if this one cannot.
     */
    fun chooseHost(hostAlias: String) {
        local.update { if (it.addingOn != null && !it.adding) it.copy(addingOn = hostAlias) else it }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Clone [url] onto the host; [onAdded] is handed the project's id. */
    fun clone(url: String, onAdded: (Long) -> Unit): Job = add(onAdded, repoName(url)) { host -> actions.clone(host, url.trim()) }

    /**
     * Add the checkout at [path] as it stands (a folder already on the host,
     * redesign 14.20). The hub adopts one on its own `local` host only, and
     * says so for any other.
     */
    fun adopt(path: String, onAdded: (Long) -> Unit): Job = add(onAdded, folderName(path)) { host -> actions.adopt(host, path.trim()) }

    /**
     * A new repository [owner]/[repo] on the host — and on GitHub when
     * [onGithub], which the hub refuses once with a token: then
     * [ProjectToolsUiState.pendingCreate] asks, and [confirmCreate] sends it back.
     */
    fun create(owner: String, repo: String, onGithub: Boolean, onAdded: (Long) -> Unit): Job {
        creating = owner.trim() to repo.trim()
        return add(onAdded, repo.trim()) { host -> actions.create(host, owner.trim(), repo.trim(), onGithub, confirm = null) }
    }

    /** The owner and repository of the last [create], for the confirmation the hub may ask for. */
    private var creating: Pair<String, String>? = null

    fun confirmCreate(onAdded: (Long) -> Unit): Job {
        val p = local.value.pendingCreate
        local.update { it.copy(pendingCreate = null) }
        return add(onAdded, p?.repo) { host ->
            requireNotNull(p) { "nothing to confirm" }
            actions.create(host, p.owner, p.repo, onGithub = true, confirm = p.token)
        }
    }

    fun cancelCreate() {
        local.update { it.copy(pendingCreate = null) }
    }

    /** Read [projectId]'s worktrees on [hostAlias]; null for either clears them. */
    fun loadWorktrees(hostAlias: String?, projectId: Long?): Job = scope.launch {
        local.update { it.copy(worktrees = null) }
        if (hostAlias == null || projectId == null || !fleet.capabilities.value.hostWorktrees) return@launch
        guarded {
            val found = actions.worktrees(hostAlias, projectId)
            local.update { it.copy(worktrees = found) }
        }
    }

    /** Delete a worktree (refused while a session lives in it), then read the list again. */
    fun deleteWorktree(worktreeId: Long): Job = scope.launch {
        val wt = local.value.worktrees ?: return@launch
        if (!state.value.canDeleteWorktree || local.value.deleting != null) return@launch
        local.update { it.copy(deleting = worktreeId, error = null) }
        guarded { actions.deleteWorktree(worktreeId) }
        local.update { it.copy(deleting = null) }
        loadWorktrees(wt.hostAlias, wt.projectId).join()
    }

    private fun add(onAdded: (Long) -> Unit, what: String?, call: suspend (String) -> dev.claudefleet.mobile.model.ProjectRow): Job = scope.launch {
        val host = local.value.addingOn ?: return@launch
        if (!state.value.canAdd || local.value.adding) return@launch
        local.update { it.copy(adding = true, addingWhat = what, addingWhere = host, error = null) }
        try {
            val row = call(host)
            local.update { it.copy(adding = false, addingOn = null, addingWhat = null, addingWhere = null) }
            // The new row reaches the list with the hub's project event; ask now too.
            runCatching { fleet.refresh() }
            onAdded(row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError.Tool) {
            val token = (e.details as? JsonObject)?.get("confirm")?.let { (it as? JsonPrimitive)?.content }
            val asked = e.code == "E_CONFIRM_REQUIRED" && token != null
            local.update {
                val done = it.copy(adding = false, addingWhat = null, addingWhere = null)
                // The ask reopens the add on its host, in case it was left to run in the background.
                if (asked) done.copy(pendingCreate = pendingFrom(token!!), addingOn = host) else done.copy(error = friendly(e))
            }
        } catch (t: Throwable) {
            local.update { it.copy(adding = false, addingWhat = null, addingWhere = null, error = friendly(t)) }
        }
    }

    private fun pendingFrom(token: String): PendingCreate {
        val (owner, repo) = creating ?: ("" to "")
        return PendingCreate(owner, repo, token)
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }
}

/** The repository a clone URL names, for the Cloning screen: `papaya-pos` from either GitHub URL form. */
internal fun repoName(url: String): String? =
    url.trim().trimEnd('/').removeSuffix(".git").substringAfterLast('/').substringAfterLast(':').takeIf { it.isNotBlank() }
