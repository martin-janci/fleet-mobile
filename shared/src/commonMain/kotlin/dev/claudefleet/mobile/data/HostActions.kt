package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.SessionRow

/**
 * What a host's sheet does: re-probe it, and bring back what a reboot took —
 * the sessions fleet still has rows for (`restore_host_sessions`) and the
 * conversations it has none for (`discover_lost_sessions`, then
 * `new_session` resuming one).
 */
interface HostActions {
    suspend fun probe(alias: String): HostRow
    suspend fun restorePlan(alias: String): RestoreReport
    suspend fun restore(alias: String): RestoreReport
    suspend fun discover(alias: String): List<LostCandidate>
    suspend fun resume(alias: String, candidate: LostCandidate): SessionRow
}

class HubHostActions(private val session: AppSession) : HostActions {
    override suspend fun probe(alias: String) = session.withClient { it.probeHost(alias) }
    override suspend fun restorePlan(alias: String) = session.withClient { it.restoreHostSessions(alias, dryRun = true) }
    override suspend fun restore(alias: String) = session.withClient { it.restoreHostSessions(alias, dryRun = false) }
    override suspend fun discover(alias: String) = session.withClient { it.discoverLostSessions(alias) }
    override suspend fun resume(alias: String, candidate: LostCandidate): SessionRow = session.withClient {
        it.newSession(
            hostAlias = alias,
            projectId = requireNotNull(candidate.projectId) { "a conversation outside every project cannot be resumed" },
            worktreeId = candidate.worktreeId,
            resumeClaudeSessionId = candidate.claudeSessionId,
            name = candidate.derivedTmuxName.orEmpty(),
        )
    }
}
