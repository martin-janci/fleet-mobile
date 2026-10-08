package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.AgentInstall

/** The hub's fleet-agent install job (claude-fleet 4.9) as the phone uses it (redesign 14.19). */
interface AgentInstallActions {
    suspend fun install(alias: String): AgentInstall
    suspend fun jobs(alias: String): List<AgentInstall>
}

class HubAgentInstallActions(private val session: AppSession) : AgentInstallActions {
    override suspend fun install(alias: String) = session.withClient { it.installAgent(alias) }
    override suspend fun jobs(alias: String) = session.withClient { it.agentInstalls(alias) }
}
