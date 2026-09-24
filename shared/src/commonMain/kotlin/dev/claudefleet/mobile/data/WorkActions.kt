package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TrackerSummary

/**
 * The work-graph reads a list screen may make (work graph M8).
 *
 * Narrow for the same reason [NewSessionActions] is: a screen never holds a
 * [dev.claudefleet.mobile.net.HubClient], so it cannot make a call that skips
 * [AppSession.withClient] and its "a 401 returns us to Pair" rule. Both are
 * `work` actions, which the hub serves to a `readonly` token too; a caller
 * still asks [dev.claudefleet.mobile.net.HubCapabilities.work] first, because
 * a hub older than the work graph has no such tool.
 */
interface WorkActions {
    /** The connected trackers. Empty: no tracker, so no "My work". */
    suspend fun trackers(): List<TrackerSummary>

    /** Tickets in one of the hub's views: `mine`, `sprint`, `recent`, `filter:<id>`. */
    suspend fun tickets(view: String): List<Ticket>
}

/** [WorkActions] against the paired hub, through [AppSession.withClient]. */
class HubWorkActions(private val session: AppSession) : WorkActions {
    override suspend fun trackers(): List<TrackerSummary> = session.withClient { it.workTrackers() }

    override suspend fun tickets(view: String): List<Ticket> = session.withClient { it.workTickets(view = view) }
}
