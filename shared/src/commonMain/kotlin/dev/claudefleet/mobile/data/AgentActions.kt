package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.SessionRow

/**
 * The fleet's agent — the desktop's ✦ button, the "UX agent" that answers
 * questions about the fleet and drives it through the hub — on the phone.
 *
 * The agent is an ordinary Claude session the hub owns (`fleet-operator` on
 * the hub's own machine), so there is nothing to build here but the way in:
 * `ensure_operator` finds it or births it and returns its row, and the app
 * opens that row on the Session screen like any other. `Access::Client` on the
 * hub, not readonly — it may start a session — so a `readonly` pairing is not
 * offered it.
 *
 * Narrow for the same reason [NewSessionActions] is: no screen holds a
 * [dev.claudefleet.mobile.net.HubClient].
 */
interface AgentActions {
    /** Find (or wake) the hub's agent session and return its row. */
    suspend fun ensureOperator(): SessionRow
}

/** [AgentActions] against the paired hub, through [AppSession.withClient]. */
class HubAgentActions(private val session: AppSession) : AgentActions {
    override suspend fun ensureOperator(): SessionRow = session.withClient { it.ensureOperator() }
}
