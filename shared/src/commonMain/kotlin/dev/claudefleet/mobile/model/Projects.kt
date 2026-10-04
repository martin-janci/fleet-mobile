package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A repository `gh` on a host can see (`list_github_repos`), to clone with Add project. */
@Serializable
data class GithubRepo(
    @SerialName("name_with_owner") val nameWithOwner: String,
    val description: String? = null,
    @SerialName("is_private") val isPrivate: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** One git worktree of a project on a host. */
@Serializable
data class WorktreeRow(
    val id: Long,
    @SerialName("project_id") val projectId: Long = 0,
    @SerialName("host_alias") val hostAlias: String = "",
    val name: String = "",
    val path: String = "",
    val branch: String? = null,
)

/** `list_host_worktrees`: a project's worktrees as they are on one host. */
@Serializable
data class HostWorktrees(
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("project_id") val projectId: Long = 0,
    /** False until the project is checked out there (a new session clones it). */
    val cloned: Boolean = false,
    val worktrees: List<WorktreeRow> = emptyList(),
)
