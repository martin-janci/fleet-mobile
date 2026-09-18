package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One project, as the app needs it: an id and a name a person recognises.
 *
 * A session row names its project by `project_id` and nothing else — there is
 * no project name anywhere on it (`crates/fleet-core/src/store/rows.rs`) — so
 * this list is the only thing that can turn `project_id: 3` into a heading.
 *
 * **Only the fields both wire shapes carry are modelled, and that is
 * deliberate.** The two sources disagree:
 *
 *  - `list_projects` (summary, the default) answers
 *    `{id, owner, repo, worktree_count, last_session_at}` — `ProjectSummary` in
 *    `mcp/tools/support.rs`.
 *  - `project:updated` carries the **store** row
 *    `{id, owner, repo, base_path, last_session_at, adopted}` — `ProjectRow` in
 *    `store/rows.rs`, via `RowChange::payload`.
 *
 * Modelling `worktree_count` would mean an event frame — which cannot carry it
 * — silently resetting it to zero on the first update after a refresh, the same
 * trap `is_controller` sprang on the session row. Nothing here draws a worktree
 * count, so nothing here holds one.
 */
@Serializable
data class ProjectRow(
    val id: Long,
    val owner: String = "",
    val repo: String = "",
    @SerialName("last_session_at") val lastSessionAt: Long? = null,
) {
    /** What a group heading says: `owner/repo`, falling back to the id. */
    val label: String
        get() = when {
            owner.isNotBlank() && repo.isNotBlank() -> "$owner/$repo"
            repo.isNotBlank() -> repo
            else -> unnamedProject(id)
        }
}

/**
 * The heading for a project the app holds no row for — a session created in a
 * project registered after the last `list_projects`, or one the hub declines to
 * list. Naming the id keeps the group honest and greppable rather than lumping
 * those sessions in with the ones that have no project at all.
 */
internal fun unnamedProject(id: Long): String = "project #$id"
