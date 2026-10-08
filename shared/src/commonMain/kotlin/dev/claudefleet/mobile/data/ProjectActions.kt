package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.model.HostWorktrees
import dev.claudefleet.mobile.model.ProjectRow

/** Adding a project on a host, and a project's worktrees there. */
interface ProjectActions {
    suspend fun clone(hostAlias: String, url: String): ProjectRow
    /** A checkout already on [hostAlias] — the hub adopts one on its own `local` host only. */
    suspend fun adopt(hostAlias: String, path: String): ProjectRow
    suspend fun create(hostAlias: String, owner: String, repo: String, onGithub: Boolean, confirm: String?): ProjectRow
    suspend fun githubRepos(hostAlias: String): List<GithubRepo>
    suspend fun worktrees(hostAlias: String, projectId: Long): HostWorktrees
    suspend fun deleteWorktree(worktreeId: Long)
}

class HubProjectActions(private val session: AppSession) : ProjectActions {
    override suspend fun clone(hostAlias: String, url: String) = session.withClient { it.addProject(hostAlias, cloneUrl = url) }
    override suspend fun adopt(hostAlias: String, path: String) = session.withClient { it.addProject(hostAlias, folderPath = path) }
    override suspend fun create(hostAlias: String, owner: String, repo: String, onGithub: Boolean, confirm: String?) =
        session.withClient { it.addProject(hostAlias, owner = owner, repo = repo, createRemote = onGithub, confirm = confirm) }
    override suspend fun githubRepos(hostAlias: String) = session.withClient { it.listGithubRepos(hostAlias) }
    override suspend fun worktrees(hostAlias: String, projectId: Long) = session.withClient { it.listHostWorktrees(hostAlias, projectId) }
    override suspend fun deleteWorktree(worktreeId: Long) = session.withClient { it.deleteWorktree(worktreeId) }
}
