package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.HostCheck
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.isSignalLost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** The hub runs the health checklist for this pairing (`check_host`). */
    val canCheck: Boolean = false,
    val probing: Boolean = false,
    val checking: Boolean = false,
    /** The last checklist read for this host; null until one is asked for. */
    val check: HostCheck? = null,
    /** The last dry run: what a restore would do. */
    val plan: RestoreReport? = null,
    val restoring: Boolean = false,
    /** The last real restore's outcome. */
    val restored: RestoreReport? = null,
    val candidates: List<LostCandidate>? = null,
    /**
     * Why there is no scan for untracked conversations to show: the host does
     * not answer, or the scan failed (review w23-10). Null while one runs or
     * once one answered.
     */
    val discoverNote: String? = null,
    val resuming: String? = null,
    val error: Friendly? = null,
    /** The host is armed: the restore runs from this phone when the hub next sees it answer. */
    val whenBack: Boolean = false,
    /** What the armed restore did when the host came back, in words; null when none has run. */
    val backNote: String? = null,
) {
    /**
     * Run plan when back is offered: the host answered once and does not now,
     * and this pairing may restore. A host never checked is not "back" from
     * anything (review w23-13).
     */
    val canRunWhenBack: Boolean get() = canRestore && host != null && host.isSignalLost

    /** Restore is offered only on a host that answers: the hub refuses it otherwise, `E_HOST_OFFLINE` (review w23-11). */
    val canRestoreNow: Boolean get() = canRestore && host?.reachable == true

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
        val checking: Boolean = false,
        val check: HostCheck? = null,
        val plan: RestoreReport? = null,
        val restoring: Boolean = false,
        val restored: RestoreReport? = null,
        val candidates: List<LostCandidate>? = null,
        val discoverNote: String? = null,
        /** The scan was held back for a host that does not answer: it runs when the host does. */
        val scanWaits: Boolean = false,
        val resuming: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    /**
     * Run plan when back (MobileMore): the hosts whose restore waits for the
     * host to answer again, and what each one's did. Held by this phone, not
     * the hub, which has no deferred restore: it runs only while the app is
     * open and connected, and the sheet says so. Kept across closing the
     * sheet, which [Local] is not.
     */
    private data class Back(val armed: Set<String> = emptySet(), val notes: Map<String, String> = emptyMap())

    private val back = MutableStateFlow(Back())

    init {
        // Arming a host that already answers runs it at once; otherwise the
        // re-list that shows it reachable again does.
        scope.launch {
            combine(fleet.hosts, back) { hosts, b -> b.armed.filter { a -> hosts.any { it.alias == a && it.reachable } } }
                .collect { due -> due.forEach { runBack(it) } }
        }
        // The sheet said the scan runs when the host answers again: keep that
        // promise while the sheet is open (review w24). An armed host's own
        // restore looks again by itself ([runBack]), so it is left to that.
        scope.launch {
            combine(fleet.hosts, local) { hosts, l ->
                l.alias?.takeIf { a -> l.scanWaits && hosts.any { it.alias == a && it.reachable } }
            }
                .distinctUntilChanged()
                .collect { alias ->
                    if (alias != null && alias !in back.value.armed) discover(alias)
                }
        }
    }

    val state: StateFlow<HostDetailUiState> = combine(local, fleet.hosts, fleet.sessions, fleet.capabilities, back) { l, hosts, rows, caps, b ->
        HostDetailUiState(
            alias = l.alias,
            host = hosts.firstOrNull { it.alias == l.alias },
            sessionCount = rows.count { it.hostAlias == l.alias },
            canProbe = caps.probeHost,
            canRestore = canWrite && caps.restoreSessions,
            canDiscover = caps.discoverLost,
            canCheck = caps.checkHost,
            probing = l.probing,
            checking = l.checking,
            check = l.check,
            plan = l.plan,
            restoring = l.restoring,
            restored = l.restored,
            candidates = l.candidates,
            discoverNote = l.discoverNote,
            resuming = l.resuming,
            error = l.error,
            whenBack = l.alias != null && l.alias in b.armed,
            backNote = l.alias?.let(b.notes::get),
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

    /** The health checklist, over the hub's SSH: a read, asked for with a tap. */
    fun check(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!fleet.capabilities.value.checkHost || local.value.checking) return@launch
        local.update { it.copy(checking = true, error = null) }
        val c = guarded { actions.check(alias) }
        local.update { if (it.alias == alias) it.copy(checking = false, check = c ?: it.check) else it }
    }

    /** The dry run, and the scan for untracked conversations — both reads. */
    fun checkLost(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        val caps = fleet.capabilities.value
        if (canWrite && caps.restoreSessions) {
            guarded { actions.restorePlan(alias) }?.let { plan -> local.update { if (it.alias == alias) it.copy(plan = plan) else it } }
        }
        if (caps.discoverLost) {
            // The scan goes over SSH: on a host that does not answer it can
            // only time out, so it waits for the host (review w23-10).
            val host = fleet.hosts.value.firstOrNull { it.alias == alias }
            if (host != null && host.isSignalLost) {
                local.update {
                    if (it.alias == alias) it.copy(discoverNote = "Untracked conversations: looked for when $alias answers again.", scanWaits = true) else it
                }
            } else {
                discover(alias)
            }
        }
    }

    /** The scan for untracked conversations on [alias], over the hub's SSH. */
    private suspend fun discover(alias: String) {
        local.update { if (it.alias == alias) it.copy(discoverNote = null, scanWaits = false) else it }
        val found = guarded { actions.discover(alias) }
        local.update {
            when {
                it.alias != alias -> it
                found != null -> it.copy(candidates = found, discoverNote = null)
                else -> it.copy(discoverNote = "Could not look for untracked conversations. Check again to retry.")
            }
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

    /**
     * Run plan when back: restore what the plan would once [HostDetailUiState.host]
     * answers again. Asked first on the sheet, as Restore is.
     */
    fun runWhenBack() {
        val alias = local.value.alias ?: return
        if (!canWrite || !fleet.capabilities.value.restoreSessions) return
        back.update { it.copy(armed = it.armed + alias, notes = it.notes - alias) }
    }

    /** Disarm Run plan when back for the open host. */
    fun cancelWhenBack() {
        val alias = local.value.alias ?: return
        back.update { it.copy(armed = it.armed - alias) }
    }

    /** The armed restore for [alias], once: disarmed before the call, so a second re-list cannot run it twice. */
    private suspend fun runBack(alias: String) {
        if (alias !in back.value.armed) return
        back.update { it.copy(armed = it.armed - alias) }
        if (!canWrite || !fleet.capabilities.value.restoreSessions) return
        val note = try {
            backNote(alias, actions.restore(alias))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            "$alias answered again, but the restore failed: ${friendly(t).body}"
        }
        back.update { it.copy(notes = it.notes + (alias to note)) }
        if (local.value.alias == alias) checkLost().join()
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

/** What an armed restore did when [alias] came back, in one line. */
internal fun backNote(alias: String, report: RestoreReport): String {
    val results = report.results
    if (results.isEmpty()) return "$alias answered again; there was nothing to restore."
    val ok = results.count { it.ok }
    return "$alias answered again: restored $ok of ${results.size}."
}
