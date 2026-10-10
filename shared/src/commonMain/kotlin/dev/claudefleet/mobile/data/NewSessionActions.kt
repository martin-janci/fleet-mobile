package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.BackgroundOptions
import dev.claudefleet.mobile.model.NewBgSessionResult
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.QueuePromptResult

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
    /** An existing worktree of the project to start in, rather than its main checkout. */
    val worktreeId: Long? = null,
    /** The login the person picked on Review: a credential profile; `null` is the host's own login. */
    val profile: String? = null,
    /** The person picked a login past the pause line, knowing it (`over_limit_ok`). */
    val overLimitOk: Boolean = false,
    /** The id this start's `start:progress` frames carry; `null` asks the hub for none. */
    val startToken: String? = null,
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

    /** A supervised headless (background) Claude session on [hostAlias], started on [prompt] (`new_bg_session`). */
    suspend fun newBackground(hostAlias: String, name: String, prompt: String): NewBgSessionResult

    /** The same with contract 14's [options]; a fake that does not care drops them. */
    suspend fun newBackground(hostAlias: String, name: String, prompt: String, options: BackgroundOptions): NewBgSessionResult =
        newBackground(hostAlias, name, prompt)

    /**
     * The logins on [hostAlias] and the room each has (`check_account_headroom`,
     * readonly): what the Review step's Account row reads. A fake that does
     * not care has none.
     */
    suspend fun headroom(hostAlias: String): Headroom = throw UnsupportedOperationException("check_account_headroom")

    /**
     * The optional first message, once the session exists (`queue_prompt`):
     * typed as soon as Claude is idle, kept by the hub until then. `new_session`
     * takes no prompt of its own.
     */
    suspend fun firstMessage(sessionId: Long, prompt: String): QueuePromptResult =
        throw UnsupportedOperationException("queue_prompt")
}

/** [NewSessionActions] against the paired hub, through [AppSession.withClient]. */
class HubNewSessionActions(private val session: AppSession) : NewSessionActions {
    override suspend fun newBackground(hostAlias: String, name: String, prompt: String): NewBgSessionResult =
        session.withClient { it.newBgSession(hostAlias, name, prompt) }

    override suspend fun newBackground(hostAlias: String, name: String, prompt: String, options: BackgroundOptions): NewBgSessionResult =
        session.withClient { it.newBgSession(hostAlias, name, prompt, options) }

    override suspend fun newSession(request: NewSessionRequest): SessionRow =
        session.withClient {
            it.newSession(
                hostAlias = request.hostAlias,
                projectId = request.projectId,
                newWorktree = request.newWorktree,
                baseBranch = request.baseBranch,
                friendlyName = request.friendlyName,
                worktreeId = request.worktreeId,
                profile = request.profile,
                overLimitOk = request.overLimitOk,
                startToken = request.startToken,
            )
        }

    override suspend fun headroom(hostAlias: String): Headroom =
        session.withClient { it.checkAccountHeadroom(hostAlias, null) }

    override suspend fun firstMessage(sessionId: Long, prompt: String): QueuePromptResult =
        session.withClient { it.queuePrompt(sessionId, prompt) }
}
