package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.RestoreReport
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

data class HostDetailUiState(
    /** The host whose sheet is open, or null when none is. */
    val alias: String? = null,
    val host: HostRow? = null,
    val sessionCount: Int = 0,
    val canProbe: Boolean = false,
    val canRestore: Boolean = false,
    val canDiscover: Boolean = false,
    val probing: Boolean = false,
    /** The last dry run: what a restore would do. */
    val plan: RestoreReport? = null,
    val restoring: Boolean = false,
    /** The last real restore's outcome. */
    val restored: RestoreReport? = null,
    val candidates: List<LostCandidate>? = null,
    val resuming: String? = null,
    val error: Friendly? = null,
) {
    /** What the plan would actually bring back. */
    val restorable: Int get() = plan?.plan?.count { it.restores } ?: 0

    /** Conversations worth offering: resumable, in a known project, not already a fleet row's. */
    val resumable: List<LostCandidate>
        get() = candidates.orEmpty().filter { it.resumable && it.projectId != null && it.existingSessionId == null }
}

/**
 * A host's sheet, the desktop's Host detail as far as the hub serves it to a
 * client: its facts and a re-probe; the sessions it lost to a reboot, as a
 * plan first (a dry run) and then a restore; and the conversations on it
 * fleet has no row for, each resumable into a new session. Reads need no
 * write grant; restore and resume do, and the screen offers them only then.
 */
class HostDetailViewModel(
    private val fleet: FleetState,
    private val actions: HostActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val alias: String? = null,
        val probing: Boolean = false,
        val plan: RestoreReport? = null,
        val restoring: Boolean = false,
        val restored: RestoreReport? = null,
        val candidates: List<LostCandidate>? = null,
        val resuming: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<HostDetailUiState> = combine(local, fleet.hosts, fleet.sessions, fleet.capabilities) { l, hosts, rows, caps ->
        HostDetailUiState(
            alias = l.alias,
            host = hosts.firstOrNull { it.alias == l.alias },
            sessionCount = rows.count { it.hostAlias == l.alias },
            canProbe = caps.probeHost,
            canRestore = canWrite && caps.restoreSessions,
            canDiscover = caps.discoverLost,
            probing = l.probing,
            plan = l.plan,
            restoring = l.restoring,
            restored = l.restored,
            candidates = l.candidates,
            resuming = l.resuming,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HostDetailUiState())

    /** Open [alias]'s sheet and look for what it lost. */
    fun open(alias: String): Job = scope.launch {
        local.value = Local(alias = alias)
        checkLost()
    }

    fun close() {
        local.value = Local()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun probe(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!fleet.capabilities.value.probeHost || local.value.probing) return@launch
        local.update { it.copy(probing = true, error = null) }
        guarded { actions.probe(alias) }
        local.update { it.copy(probing = false) }
    }

    /** The dry run, and the scan for untracked conversations — both reads. */
    fun checkLost(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        val caps = fleet.capabilities.value
        if (canWrite && caps.restoreSessions) {
            guarded { actions.restorePlan(alias) }?.let { plan -> local.update { if (it.alias == alias) it.copy(plan = plan) else it } }
        }
        if (caps.discoverLost) {
            guarded { actions.discover(alias) }?.let { found -> local.update { if (it.alias == alias) it.copy(candidates = found) else it } }
        }
    }

    /** Restore what the plan would; then look again, for what is still lost. */
    fun restore(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!state.value.canRestore || local.value.restoring) return@launch
        local.update { it.copy(restoring = true, error = null) }
        val done = guarded { actions.restore(alias) }
        local.update { it.copy(restoring = false, restored = done ?: it.restored) }
        if (done != null) checkLost().join()
    }

    /** Resume [candidate]'s conversation in a new session, and hand its id over to open. */
    fun resume(candidate: LostCandidate, onStarted: (Long) -> Unit): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!canWrite || local.value.resuming != null) return@launch
        local.update { it.copy(resuming = candidate.claudeSessionId, error = null) }
        val row = guarded { actions.resume(alias, candidate) }
        local.update { it.copy(resuming = null) }
        if (row != null) onStarted(row.id)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        local.update { it.copy(error = friendly(t)) }
        null
    }
}
