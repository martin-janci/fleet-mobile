package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.MoveOutcome

/** Moving a session to another host (`move_session`). */
interface MoveActions {
    suspend fun preview(sessionId: Long, targetHost: String, keepSource: Boolean): MoveOutcome
    suspend fun move(sessionId: Long, targetHost: String, keepSource: Boolean, whenIdle: Boolean): MoveOutcome
    suspend fun cancelWait(sessionId: Long, targetHost: String): MoveOutcome
}

class HubMoveActions(private val session: AppSession) : MoveActions {
    override suspend fun preview(sessionId: Long, targetHost: String, keepSource: Boolean) =
        session.withClient { it.moveSession(sessionId, targetHost, keepSource, dryRun = true) }
    override suspend fun move(sessionId: Long, targetHost: String, keepSource: Boolean, whenIdle: Boolean) =
        session.withClient { it.moveSession(sessionId, targetHost, keepSource, whenIdle = whenIdle) }
    override suspend fun cancelWait(sessionId: Long, targetHost: String) =
        session.withClient { it.moveSession(sessionId, targetHost, cancelWait = true) }
}
