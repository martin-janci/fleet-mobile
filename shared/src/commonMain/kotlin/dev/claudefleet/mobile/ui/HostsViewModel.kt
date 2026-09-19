package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One machine, as the Hosts screen draws it. */
data class HostLine(
    val alias: String,
    val reachable: Boolean,
    /** Null when the host has never been probed. `""` would be a version. */
    val claudeVersion: String?,
    val tmuxVersion: String?,
    /** How many of the fleet's sessions live here. */
    val sessions: Int,
    /** Hidden from the desktop sidebar. Shown here anyway; see the class comment. */
    val hidden: Boolean,
    /** `ssh` | `agent` — how the hub reaches it. */
    val transport: String,
)

data class HostsUiState(
    val hosts: List<HostLine> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = hosts.isEmpty()
}

/**
 * The machines: whether the hub can reach each one, what Claude and tmux it
 * found there, and how many sessions are on it.
 *
 * Read-only, and that is not an omission. Adding, removing and hiding a host
 * are fleet administration, which the hub refuses a client token, so the screen
 * offers none of them — the app never calls a tool it knows would be refused.
 *
 * A **hidden** host is listed rather than dropped. `hidden` is the desktop
 * sidebar's own "do not show me this", the desktop's Hosts view lists hidden
 * hosts too (it is where the toggle lives), and this app cannot unhide one. A
 * hidden host with live sessions would otherwise put those sessions on the
 * fleet list under a machine name with nowhere to look it up.
 */
class HostsViewModel(
    private val fleet: FleetState,
    private val scope: CoroutineScope,
) {
    private data class Local(val refreshing: Boolean = false, val error: String? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<HostsUiState> =
        combine(fleet.hosts, fleet.sessions, fleet.status, local) { hosts, sessions, status, l ->
            assemble(hosts, sessions, status, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(fleet.hosts.value, fleet.sessions.value, fleet.status.value, local.value),
        )


    /** Clear the banner. See [SessionsViewModel.dismissError]. */
    fun dismissError() {
        local.update { it.copy(error = null) }
    }
    /** Re-list. The rows stay put if it fails; the last picture is still the best one. */
    fun refresh(): Job = scope.launch {
        local.update { Local(refreshing = true) }
        try {
            fleet.refresh()
            local.update { Local() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { Local(error = explain(t)) }
        }
    }

    private fun assemble(
        hosts: List<HostRow>,
        sessions: List<SessionRow>,
        status: ConnectionStatus,
        l: Local,
    ): HostsUiState {
        val counts = sessions.groupingBy { it.hostAlias }.eachCount()
        return HostsUiState(
            hosts = hosts.sortedWith(BY_ALIAS).map { host ->
                HostLine(
                    alias = host.alias,
                    reachable = host.reachable,
                    claudeVersion = host.claudeVersion?.takeIf { it.isNotBlank() },
                    tmuxVersion = host.tmuxVersion?.takeIf { it.isNotBlank() },
                    sessions = counts[host.alias] ?: 0,
                    hidden = host.hidden,
                    transport = host.transport,
                )
            },
            status = status,
            refreshing = l.refreshing,
            error = l.error,
        )
    }
}

/**
 * The hub's own order — `ORDER BY (alias='local') DESC, alias ASC` in
 * `store/hosts_accounts.rs` — reimposed here rather than inherited.
 *
 * `list_hosts` arrives sorted, but a `host:added` frame *appends* to the
 * snapshot (`FleetSnapshot.upsertHost`), so after one event the hub's order is
 * gone and the list reshuffles under a thumb on the next refetch. Sorting on
 * this side costs one pass and makes the order a property of the screen.
 */
private val BY_ALIAS: Comparator<HostRow> =
    compareBy<HostRow>({ it.alias != LOCAL }, { it.alias })

/** The machine the hub itself runs on. */
private const val LOCAL = "local"
