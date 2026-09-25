package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.Today

/**
 * The work-graph calls a screen may make — narrow for the same reason
 * [SessionActions] is: no screen holds a [dev.claudefleet.mobile.net.HubClient],
 * so none can skip [AppSession.withClient] and its "a 401 returns us to Pair".
 *
 * The reads are `work`, readonly on the hub. The rest are `work_link`, which
 * the hub hides from a readonly token; a screen offers them only when the
 * credential can write **and** [FleetState.capabilities] lists the action —
 * the app never calls a tool its token may not use.
 */
interface WorkActions {
    /** Tickets in one of the hub's views: `mine`, `sprint`, `recent`. */
    suspend fun tickets(view: String): List<Ticket>

    /** One ticket by key or pasted URL. */
    suspend fun lookup(keyOrUrl: String): Ticket

    /** What resuming [key] would do. */
    suspend fun resumePlan(key: String): ResumePlan

    /** The Today digest since [since], unix seconds. */
    suspend fun today(since: Long): Today

    /** [key]'s context card: acceptance criteria from the hub's cache. */
    suspend fun card(key: String): TicketCard

    suspend fun confirm(sessionId: Long, linkId: Long): SessionRow

    suspend fun reject(sessionId: Long, linkId: Long): SessionRow

    suspend fun unlink(sessionId: Long, linkId: Long): SessionRow

    /** Set a session's work: a looked-up ticket by [itemId], or a bare [key]. */
    suspend fun link(sessionId: Long, itemId: Long? = null, key: String? = null): SessionRow

    /** Start work on [key] on [hostAlias]; [projectId] null lets the hub pick. */
    suspend fun start(key: String, hostAlias: String, projectId: Long? = null): SessionRow

    /** Resume [key]'s last conversation, on [hostAlias] or where the hub would put it. */
    suspend fun resume(key: String, hostAlias: String? = null): SessionRow

    /** Ask the session's Claude for a handover note on its work; the answer comes later. */
    suspend fun handover(sessionId: Long): SessionRow
}

/** [WorkActions] against the paired hub, through [AppSession.withClient]. */
class HubWorkActions(private val session: AppSession) : WorkActions {
    override suspend fun tickets(view: String): List<Ticket> = session.withClient { it.workTickets(view) }

    override suspend fun lookup(keyOrUrl: String): Ticket = session.withClient { it.workLookup(keyOrUrl) }

    override suspend fun resumePlan(key: String): ResumePlan = session.withClient { it.workResumePlan(key) }

    override suspend fun today(since: Long): Today = session.withClient { it.workToday(since) }

    override suspend fun card(key: String): TicketCard = session.withClient { it.workCard(key) }

    override suspend fun confirm(sessionId: Long, linkId: Long): SessionRow =
        session.withClient { it.confirmWork(sessionId, linkId) }

    override suspend fun reject(sessionId: Long, linkId: Long): SessionRow =
        session.withClient { it.rejectWork(sessionId, linkId) }

    override suspend fun unlink(sessionId: Long, linkId: Long): SessionRow =
        session.withClient { it.unlinkWork(sessionId, linkId) }

    override suspend fun link(sessionId: Long, itemId: Long?, key: String?): SessionRow =
        session.withClient { it.linkWork(sessionId, itemId, key) }

    override suspend fun start(key: String, hostAlias: String, projectId: Long?): SessionRow =
        session.withClient { it.startWork(key, hostAlias, projectId) }

    override suspend fun resume(key: String, hostAlias: String?): SessionRow =
        session.withClient { it.resumeWork(key, mode = "last", hostAlias = hostAlias) }

    override suspend fun handover(sessionId: Long): SessionRow = session.withClient { it.handoverWork(sessionId) }
}
