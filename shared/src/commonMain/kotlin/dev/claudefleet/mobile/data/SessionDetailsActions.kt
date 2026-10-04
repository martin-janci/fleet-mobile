package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow

/**
 * The calls a session's Details sheet makes — narrow for the reason
 * [SessionActions] is. All but [cancelTask] are readonly tools, so a
 * `readonly` pairing reads everything here; whether the hub has each one is
 * `HubCapabilities.sessionHistory` / `relatedSessions` / `tasks` /
 * `cancelTask`.
 */
interface SessionDetailsActions {
    suspend fun history(sessionId: Long): List<SessionEvent>
    suspend fun related(sessionId: Long): List<SessionRow>
    suspend fun tasks(): List<FleetTask>
    suspend fun cancelTask(taskId: Long)
}

class HubSessionDetailsActions(private val session: AppSession) : SessionDetailsActions {
    override suspend fun history(sessionId: Long): List<SessionEvent> = session.withClient { it.sessionHistory(sessionId) }
    override suspend fun related(sessionId: Long): List<SessionRow> = session.withClient { it.relatedSessions(sessionId) }
    override suspend fun tasks(): List<FleetTask> = session.withClient { it.listTasks() }
    override suspend fun cancelTask(taskId: Long) = session.withClient { it.cancelTask(taskId) }
}
