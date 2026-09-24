package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionRow

/**
 * What the New session form sends: already trimmed, with every optional field
 * the person left blank as `null` — see [dev.claudefleet.mobile.net.HubClient.newSession].
 */
data class NewSessionRequest(
    val hostAlias: String,
    val projectId: Long,
    /** A branch to fork a fresh worktree for; `null` runs in the project root. */
    val newWorktree: String? = null,
    /** What [newWorktree] forks from; `null` is the repository's default branch. */
    val baseBranch: String? = null,
    /** The sidebar label; `null` lets the hub derive one from the branch. */
    val friendlyName: String? = null,
)

/**
 * The one call the New session form may make.
 *
 * Narrow for the same reason [SessionActions] is: the form never holds a
 * [dev.claudefleet.mobile.net.HubClient], so it cannot make a call that skips
 * [AppSession.withClient] and its "a 401 returns us to Pair" rule. `new_session`
 * is fleet-wide session control — a `full` client has it and a `readonly` one
 * does not, which is why the form is only offered to the first.
 */
interface NewSessionActions {
    /** Create the session and return its row, as the hub stored it. */
    suspend fun newSession(request: NewSessionRequest): SessionRow
}

/** [NewSessionActions] against the paired hub, through [AppSession.withClient]. */
class HubNewSessionActions(private val session: AppSession) : NewSessionActions {
    override suspend fun newSession(request: NewSessionRequest): SessionRow =
        session.withClient {
            it.newSession(
                hostAlias = request.hostAlias,
                projectId = request.projectId,
                newWorktree = request.newWorktree,
                baseBranch = request.baseBranch,
                friendlyName = request.friendlyName,
            )
        }
}
