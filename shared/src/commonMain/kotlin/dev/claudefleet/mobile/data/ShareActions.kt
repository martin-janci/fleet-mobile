package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionGrant
import dev.claudefleet.mobile.model.ShareTo

/**
 * The owner's share sheet (redesign 11.10): who holds a grant on a session,
 * and share, narrow and revoke. The hub refuses all four to anyone but the
 * session's owner.
 */
interface ShareActions {
    suspend fun access(sessionId: Long): List<SessionGrant>

    suspend fun share(sessionId: Long, to: ShareTo, level: String)

    suspend fun narrow(sessionId: Long, to: ShareTo)

    suspend fun revoke(sessionId: Long, to: ShareTo)
}

class HubShareActions(private val session: AppSession) : ShareActions {
    override suspend fun access(sessionId: Long): List<SessionGrant> = session.withClient { it.sessionAccess(sessionId) }

    override suspend fun share(sessionId: Long, to: ShareTo, level: String) = session.withClient { it.shareSession(sessionId, to, level) }

    override suspend fun narrow(sessionId: Long, to: ShareTo) = session.withClient { it.narrowShare(sessionId, to) }

    override suspend fun revoke(sessionId: Long, to: ShareTo) = session.withClient { it.unshareSession(sessionId, to) }
}
