package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AddHostActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SshHost
import dev.claudefleet.mobile.ui.kit.RadarBlip
import dev.claudefleet.mobile.ui.kit.radarSpot
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

data class AddHostUiState(
    /** The hub lists `add_host` and `discover_hosts` to this token, and the phone may write. */
    val available: Boolean = false,
    val open: Boolean = false,
    val scanning: Boolean = false,
    /** The hub's SSH config hosts the fleet does not have yet. */
    val candidates: List<SshHost> = emptyList(),
    /** The candidate being probed and added, by its SSH alias. */
    val adding: String? = null,
    /** SSH aliases added from this screen. */
    val added: Set<String> = emptySet(),
    val error: Friendly? = null,
    /** The scan of the hub's SSH config failed: "None new" would be a claim it cannot make. */
    val scanFailed: Boolean = false,
    /** What the fleet lists for each host added here, by SSH alias: its probe, tmux included. */
    val addedRows: Map<String, HostRow> = emptyMap(),
    /** This pairing may start the hub's fleet-agent install job (`install_agent`). */
    val canInstallAgent: Boolean = false,
) {
    /**
     * The fleet host an added row offers Install agent for: the hub reaches
     * it over SSH and it answered. Null for a row not added here, or one the
     * job cannot run on.
     */
    fun installTarget(sshAlias: String): HostRow? =
        addedRows[sshAlias]?.takeIf { sshAlias in added && canInstallOn(it, canWrite = canInstallAgent, hubOffers = canInstallAgent) }

    /** One blip per host the sweep found; an added one reads as ready. */
    val blips: List<RadarBlip> get() = candidates.mapIndexed { i, h ->
        val (x, y) = radarSpot(i)
        RadarBlip(x, y, ready = h.alias in added)
    }
}

/**
 * Adding a host from the phone (redesign 14.12's Radar, claude-fleet
 * contract 13): the hub's `~/.ssh/config` entries the fleet does not have,
 * each with Add. The hub probes a host before it keeps it and refuses a
 * phone it does not trust; that refusal says which command trusts it.
 */
class AddHostViewModel(
    private val fleet: FleetState,
    private val actions: AddHostActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val scanning: Boolean = false,
        val found: List<SshHost> = emptyList(),
        val adding: String? = null,
        val added: Set<String> = emptySet(),
        val error: Friendly? = null,
        val scanFailed: Boolean = false,
        /** The row `add_host` answered, by SSH alias: the fleet alias to follow it under. */
        val rows: Map<String, HostRow> = emptyMap(),
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AddHostUiState> = combine(local, fleet.capabilities, fleet.hosts) { l, caps, hosts ->
        AddHostUiState(
            available = canWrite && caps.addHost,
            open = l.open,
            scanning = l.scanning,
            candidates = newHosts(l.found, hosts, l.added),
            adding = l.adding,
            added = l.added,
            error = l.error,
            scanFailed = l.scanFailed,
            // The fleet's own row once it lists it (a re-probe moves it on), else what add_host answered.
            addedRows = l.rows.mapValues { (_, row) -> hosts.firstOrNull { it.alias == row.alias } ?: row },
            canInstallAgent = canWrite && caps.installAgent,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AddHostUiState())

    fun open(): Job = scope.launch {
        if (!state.value.available) return@launch
        local.value = Local(open = true, scanning = true)
        scan()
    }

    /** Retry after a failed scan: reads the hub's SSH config again, keeping what was added. */
    fun rescan(): Job = scope.launch {
        if (!local.value.open) return@launch
        local.update { it.copy(scanning = true, scanFailed = false, error = null) }
        scan()
    }

    private suspend fun scan() {
        try {
            val found = actions.candidates()
            local.update { if (it.open) it.copy(scanning = false, found = found, scanFailed = false) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { if (it.open) it.copy(scanning = false, error = friendly(t), scanFailed = true) else it }
        }
    }

    fun close() {
        local.value = Local()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Add [host] under a fleet alias made from its SSH alias; the fleet is read again so it lists it. */
    fun add(host: SshHost): Job = scope.launch {
        if (!state.value.available || local.value.adding != null || host.alias in local.value.added) return@launch
        local.update { it.copy(adding = host.alias, error = null) }
        try {
            val row = actions.add(fleetAlias(host.alias), host.alias)
            local.update { it.copy(adding = null, added = it.added + host.alias, rows = it.rows + (host.alias to row)) }
            fleet.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(adding = null, error = friendly(t)) }
        }
    }
}

/**
 * The SSH config hosts the fleet does not have: neither a host's alias nor
 * its SSH alias. One added from here stays listed (as Added) after the fleet
 * starts listing it, so the row does not jump away under the finger.
 */
internal fun newHosts(found: List<SshHost>, hosts: List<HostRow>, added: Set<String>): List<SshHost> {
    val known = hosts.flatMap { listOfNotNull(it.alias, it.sshAlias) }.toSet()
    return found.filter { it.alias in added || it.alias !in known }
}

/** A fleet alias is letters, digits and dashes (the hub's rule); anything else in an SSH alias becomes a dash. */
internal fun fleetAlias(sshAlias: String): String =
    sshAlias.map { if (it.isLetterOrDigit() || it == '-') it else '-' }.joinToString("").trim('-').ifEmpty { "host" }

/** What a found host's row says under its name: "martin@10.0.0.5:2222", or "WSL: Ubuntu". */
internal fun sshHostLine(h: SshHost): String {
    val host = h.hostname?.takeIf { it.isNotBlank() } ?: h.alias
    if (host.startsWith("WSL: ")) return host
    return (h.user?.takeIf { it.isNotBlank() }?.let { "$it@" } ?: "") + host + (h.port?.takeIf { it != 22 }?.let { ":$it" } ?: "")
}
