package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The fleet's live picture, as a screen sees it.
 *
 * [FleetRepository] is the one implementation; this exists so a view model can
 * be tested against four flows and a counter instead of a mock HTTP engine and
 * a fake event stream — the same seam `EventStream` puts under itself for the
 * same reason: the thing being tested here is how rows become groups, not
 * how bytes become rows.
 *
 * It is also a narrowing. A screen gets no `HubClient` through this interface
 * and therefore cannot make a call that skips `AppSession.withClient` and its
 * "a 401 returns us to Pair" rule.
 */
interface FleetState {
    val sessions: StateFlow<List<SessionRow>>
    val hosts: StateFlow<List<HostRow>>
    val projects: StateFlow<List<ProjectRow>>
    val status: StateFlow<ConnectionStatus>

    /**
     * The id of a session the hub just reported a row change for —
     * `session:created`, `session:updated` or `session:killed` — one at a time,
     * as they arrive. A `ready` or `lagged` resync also emits
     * [dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED] once its own refetch
     * has landed, since a resync has no per-row events for a screen to key on.
     *
     * A hot flow, not a `StateFlow`: there is no "current" changed session, only
     * a sequence of them, and a screen that was not collecting when one fired
     * simply missed it, same as it would miss the row event itself. A session
     * screen uses this to know when to refetch its conversation; it is *not* the
     * conversation, which stays a `session_conversation` call.
     */
    val sessionChanges: Flow<Long>

    /** Re-list everything. Raises rather than swallowing, so a pull-to-refresh can say it failed. */
    suspend fun refresh()
}
