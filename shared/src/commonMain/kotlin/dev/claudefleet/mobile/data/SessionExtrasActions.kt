package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionRow

/**
 * A session's extras on the New bar (redesign 14.14): plain shells beside it
 * (`new_shell_session`, read with `capture_session`, typed into with
 * `send_prompt`) and Archive from its ⋮ menu.
 */
interface SessionExtrasActions {
    suspend fun newShell(hostAlias: String, projectId: Long, worktreeId: Long?, name: String): SessionRow
    suspend fun capture(sessionId: Long, maxLines: Int): String
    suspend fun type(sessionId: Long, text: String)
    suspend fun press(sessionId: Long, key: String)
    suspend fun archive(sessionId: Long)
}

class HubSessionExtrasActions(private val session: AppSession) : SessionExtrasActions {
    override suspend fun newShell(hostAlias: String, projectId: Long, worktreeId: Long?, name: String) =
        session.withClient { it.newShellSession(hostAlias, projectId, worktreeId, name) }
    override suspend fun capture(sessionId: Long, maxLines: Int) = session.withClient { it.capture(sessionId, maxLines) }
    override suspend fun type(sessionId: Long, text: String) {
        session.withClient { it.sendPrompt(sessionId, text) }
    }
    override suspend fun press(sessionId: Long, key: String) {
        session.withClient { it.sendKeys(sessionId, key) }
    }
    override suspend fun archive(sessionId: Long) = session.withClient { it.archiveSession(sessionId) }
}
