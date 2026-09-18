package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.flow.StateFlow

/**
 * The fleet's live picture, as a screen sees it.
 *
 * [FleetRepository] is the one implementation; this exists so a view model can
 * be tested against four flows and a counter instead of a mock HTTP engine and
 * a fake event stream. It is the same seam Task 4 put under `EventStream` for
 * the same reason: the thing being tested here is how rows become groups, not
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

    /** Re-list everything. Raises rather than swallowing, so a pull-to-refresh can say it failed. */
    suspend fun refresh()
}
