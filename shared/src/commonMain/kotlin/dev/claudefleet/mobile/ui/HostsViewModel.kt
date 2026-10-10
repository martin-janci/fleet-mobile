package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.kit.StatusWord
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
    /** When the hub last reached it: what an unreachable row says it has been gone since. */
    val lastPingedAt: Long? = null,
    /** Of [sessions], how many need a person, and how many are working: the New layout's line. */
    val needsYou: Int = 0,
    val working: Int = 0,
    /** The round trip of the hub's last probe, in ms; null for `local` and from an older hub. */
    val latencyMs: Long? = null,
)

data class HostsUiState(
    val hosts: List<HostLine> = emptyList(),
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    val refreshing: Boolean = false,
    val error: Friendly? = null,
    /** Hosts a Try again or Check now is probing right now. */
    val checking: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = hosts.isEmpty()

    /**
     * Nothing listed because nothing has arrived yet, not because the fleet
     * has no hosts: "No hosts" then sent a new user off to add hosts they had.
     */
    val connecting: Boolean
        get() = hosts.isEmpty() && (status is ConnectionStatus.Offline || status is ConnectionStatus.Reconnecting)
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
    /** Try again and Check now on a row (New layout); null leaves the screen read-only. */
    private val actions: HostActions? = null,
) {
    private data class Local(
        val refreshing: Boolean = false,
        val error: Friendly? = null,
        val checking: Set<String> = emptySet(),
    )

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
    /**
     * Re-list. The rows stay put if it fails; the last picture is still the best one.
     *
     * Its three `update {}` calls each construct a fresh `Local(...)` rather
     * than `it.copy(...)`: `refresh()` owns the refreshing flag and the error
     * for the whole span between its first update and its last. Only
     * `checking` is carried over, because [check] runs alongside and owns it.
     * [dismissError] and [check] are read-modify-writes, because they can land
     * in the middle of that span and must not clobber whichever of these
     * three just ran.
     */
    fun refresh(): Job = scope.launch {
        local.update { Local(refreshing = true, checking = it.checking) }
        try {
            fleet.refresh()
            local.update { Local(checking = it.checking) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { Local(error = friendly(t), checking = it.checking) }
        }
    }

    /**
     * Probe one host now: Try again on a lost host, Check now on one never
     * checked. A read on the host's side (`probe_host`), so it needs no write
     * token; the row says Checking… until the answer, then the re-list shows
     * what the probe found.
     */
    fun check(alias: String): Job? {
        val probe = actions ?: return null
        if (!fleet.capabilities.value.probeHost || alias in local.value.checking) return null
        return scope.launch {
            local.update { it.copy(checking = it.checking + alias, error = null) }
            try {
                probe.probe(alias)
                fleet.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(error = friendly(t)) }
            } finally {
                local.update { it.copy(checking = it.checking - alias) }
            }
        }
    }

    private fun assemble(
        hosts: List<HostRow>,
        sessions: List<SessionRow>,
        status: ConnectionStatus,
        l: Local,
    ): HostsUiState {
        val counts = sessions.groupingBy { it.hostAlias }.eachCount()
        val words = sessions.groupBy { it.hostAlias }.mapValues { (_, rows) -> rows.map { phoneWord(it) } }
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
                    lastPingedAt = host.lastPingedAt,
                    needsYou = words[host.alias]?.count { it == StatusWord.NEEDS_YOU } ?: 0,
                    working = words[host.alias]?.count { it == StatusWord.WORKING } ?: 0,
                    latencyMs = host.latencyMs,
                )
            },
            status = status,
            refreshing = l.refreshing,
            error = l.error,
            checking = l.checking,
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
 * The New session form offers its hosts in the same order.
 */
internal val BY_ALIAS: Comparator<HostRow> =
    compareBy<HostRow>({ it.alias != LOCAL }, { it.alias })

/** The machine the hub itself runs on. */
private const val LOCAL = "local"

/**
 * What the hub's last probe found on [host], in words: "212 GB free · 16
 * CPUs · 64 GB memory". One line, read by the host's sheet and by Add a
 * project's Where step. Only what the hub sampled: a fact it never read is
 * left out, and null means it read none of them. The hub probes no
 * toolchains (a JDK, an SDK), so none is claimed.
 */
internal fun hostFacts(host: HostRow): String? = listOfNotNull(
    host.diskHomeFreeKb?.takeIf { it >= 0 }?.let { "${gigabytes(it)} free" },
    host.cpuCount?.takeIf { it > 0 }?.let { if (it == 1) "1 CPU" else "$it CPUs" },
    host.memTotalKb?.takeIf { it > 0 }?.let { "${gigabytes(it)} memory" },
).joinToString(" · ").ifEmpty { null }

/** [kb] kibibytes as "212 GB", or "3.4 GB" under ten; "0.2 GB" rather than "0 GB" for a nearly full disk. */
internal fun gigabytes(kb: Long): String {
    val gb = kb * 1024.0 / 1_000_000_000.0
    return if (gb >= 10) "${gb.toLong()} GB" else "${(gb * 10).toLong() / 10.0} GB"
}
