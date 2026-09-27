package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.TreePage
import dev.claudefleet.mobile.model.WorkFilters
import dev.claudefleet.mobile.model.WorkView

/**
 * The Work view's reads (claude-fleet M14.1b): `work { tree | task |
 * session_tasks | views }`, readonly on the hub. Narrow for the same reason
 * [WorkActions] is — no screen holds a client, so none can skip
 * [AppSession.withClient].
 *
 * Reads only. The Work view's edits (`set_primary`, `place`, the review
 * decisions, `view_save`) are the second phone PR; Continue and Start here
 * reuse [WorkActions.resume] and the New session form.
 *
 * Every read is fenced by the token's org on the hub: a phone paired with
 * `fleet-hub pair --org` gets its org's rows (and unassigned ones while the
 * org's D31 flag is on) and nothing that says other orgs exist.
 */
interface WorkViewActions {
    suspend fun tree(filters: WorkFilters, cursor: String? = null, limit: Int? = null, perTask: Int? = null): TreePage

    suspend fun task(taskId: String): TaskDetail

    suspend fun sessionTasks(sessionId: Long): SessionTasks

    suspend fun views(): List<WorkView>
}

/** [WorkViewActions] against the paired hub, through [AppSession.withClient]. */
class HubWorkViewActions(private val session: AppSession) : WorkViewActions {
    override suspend fun tree(filters: WorkFilters, cursor: String?, limit: Int?, perTask: Int?): TreePage =
        session.withClient { it.workTree(filters.toJson(), cursor, limit, perTask) }

    override suspend fun task(taskId: String): TaskDetail = session.withClient { it.workTask(taskId) }

    override suspend fun sessionTasks(sessionId: Long): SessionTasks = session.withClient { it.workSessionTasks(sessionId) }

    override suspend fun views(): List<WorkView> = session.withClient { it.workViews() }
}
