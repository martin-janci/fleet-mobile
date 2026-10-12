package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AgentAction
import dev.claudefleet.mobile.model.AgentsStatus

/** The agents' calls the Inbox makes (the hub's `agents` tool, the owner's own device only). */
interface BuiltInAgentActions {
    suspend fun status(): AgentsStatus

    /** Approve (`true`) or reject an action; the action as it is afterwards. */
    suspend fun decide(id: Long, approve: Boolean): AgentAction
}

/** [BuiltInAgentActions] against the paired hub, through [AppSession.withClient]. */
class HubBuiltInAgentActions(private val session: AppSession) : BuiltInAgentActions {
    override suspend fun status(): AgentsStatus = session.withClient { it.agentsStatus() }

    override suspend fun decide(id: Long, approve: Boolean): AgentAction =
        session.withClient { it.decideAgentAction(id, approve) }
}
