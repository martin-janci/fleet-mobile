package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.HostCheck
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.describeLoginConflict
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
    /**
     * The conversations (`claude_session_id`) the hub refused to resume as
     * past the login's pause line: each offers "Start anyway" (review w12-2).
     * Never a spent daily limit, which cannot be started anyway.
     */
    val overLimit: Set<String> = emptySet(),
    /** The host's login conflicts, one sentence each (review w12-4); empty when none, or from an older hub. */
    val loginConflicts: List<String> = emptyList(),
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
        val candidates: List<LostCandidate>? = null,
        val discoverNote: String? = null,
        /** The scan was held back for a host that does not answer: it runs when the host does. */
        val scanWaits: Boolean = false,
        val resuming: String? = null,
        val overLimit: Set<String> = emptySet(),
        val loginConflicts: List<String> = emptyList(),
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
    private data class Back(
        val armed: Set<String> = emptySet(),
        val notes: Map<String, String> = emptyMap(),
        /** Armed hosts whose restore is under way: no longer [armed], not yet done. */
        val running: Set<String> = emptySet(),
        /**
         * Hosts whose Restore (the sheet's button) is under way, and each
         * host's last outcome: kept by host, so a restore that ends while
         * another host's sheet is open reports on its own host, and a sheet
         * reopened meanwhile shows Restoring… (review phone-flows-6).
         */
        val restoring: Set<String> = emptySet(),
        val restored: Map<String, RestoreReport> = emptyMap(),
    )

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
                    val b = back.value
                    if (alias != null && alias !in b.armed && alias !in b.running) discover(alias)
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
            restoring = l.alias != null && l.alias in b.restoring,
            restored = l.alias?.let(b.restored::get),
            candidates = l.candidates,
            discoverNote = l.discoverNote,
            resuming = l.resuming,
            overLimit = l.overLimit,
            loginConflicts = l.loginConflicts,
            error = l.error,
            whenBack = l.alias != null && l.alias in b.armed,
            backNote = l.alias?.let(b.notes::get),
        )
    }.stateIn(scope, SharingStarted.Eagerly, HostDetailUiState())

    /** Open [alias]'s sheet and look for what it lost. */
    fun open(alias: String): Job = scope.launch {
        local.value = Local(alias = alias)
        launch { readLoginConflicts(alias) }
        checkLost()
    }

    /** What the hub says about two copies of one login on [alias]; quiet when it cannot say. */
    private suspend fun readLoginConflicts(alias: String) {
        val lines = try {
            actions.loginConflicts(alias).mapNotNull { describeLoginConflict(it, alias) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return
        }
        local.update { if (it.alias == alias) it.copy(loginConflicts = lines) else it }
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
        guarded(alias) { actions.probe(alias) }
        local.update { if (it.alias == alias) it.copy(probing = false) else it }
    }

    /** The health checklist, over the hub's SSH: a read, asked for with a tap. */
    fun check(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!fleet.capabilities.value.checkHost || local.value.checking) return@launch
        local.update { it.copy(checking = true, error = null) }
        val c = guarded(alias) { actions.check(alias) }
        local.update { if (it.alias == alias) it.copy(checking = false, check = c ?: it.check) else it }
    }

    /** The dry run, and the scan for untracked conversations — both reads. */
    fun checkLost(): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        val caps = fleet.capabilities.value
        if (canWrite && caps.restoreSessions) {
            guarded(alias) { actions.restorePlan(alias) }?.let { plan -> local.update { if (it.alias == alias) it.copy(plan = plan) else it } }
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
        val found = guarded(alias) { actions.discover(alias) }
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
        if (!state.value.canRestore || alias in back.value.restoring) return@launch
        back.update { it.copy(restoring = it.restoring + alias) }
        local.update { it.copy(error = null) }
        val done = try {
            guarded(alias) { actions.restore(alias) }
        } catch (e: CancellationException) {
            back.update { it.copy(restoring = it.restoring - alias) }
            throw e
        }
        back.update { b ->
            b.copy(restoring = b.restoring - alias, restored = if (done != null) b.restored + (alias to done) else b.restored)
        }
        // Look again only on the host the restore was for: another host's
        // sheet may be open by now.
        if (done != null && local.value.alias == alias) checkLost().join()
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
        if (!canWrite || !fleet.capabilities.value.restoreSessions) {
            back.update { it.copy(armed = it.armed - alias) }
            return
        }
        // Marked running in the same step that disarms it, so the waiting
        // scan does not take "no longer armed" for "nothing will look".
        back.update { it.copy(armed = it.armed - alias, running = it.running + alias) }
        val note = try {
            backNote(alias, actions.restore(alias))
        } catch (e: CancellationException) {
            back.update { it.copy(running = it.running - alias) }
            throw e
        } catch (t: Throwable) {
            "$alias answered again, but the restore failed: ${friendly(t).body}"
        }
        back.update { it.copy(notes = it.notes + (alias to note), running = it.running - alias) }
        if (local.value.alias == alias) checkLost().join()
    }

    /**
     * Resume [candidate]'s conversation in a new session, and hand its id over
     * to open. [overLimitOk] only from "Start anyway", on a conversation the
     * hub refused as past its login's pause line ([HostDetailUiState.overLimit]).
     */
    fun resume(candidate: LostCandidate, overLimitOk: Boolean = false, onStarted: (Long) -> Unit): Job = scope.launch {
        val alias = local.value.alias ?: return@launch
        if (!canWrite || local.value.resuming != null) return@launch
        val id = candidate.claudeSessionId
        if (overLimitOk && id !in local.value.overLimit) return@launch
        local.update { it.copy(resuming = id, error = null) }
        val row = try {
            actions.resume(alias, candidate, overLimitOk)
        } catch (e: CancellationException) {
            local.update { it.copy(resuming = null) }
            throw e
        } catch (t: Throwable) {
            // Past the pause line: the person may start it anyway. A spent
            // daily limit says where to raise it instead (`canStartAnyway`).
            local.update {
                if (it.alias != alias) it.copy(resuming = null)
                else it.copy(resuming = null, error = friendly(t), overLimit = if (canStartAnyway(t)) it.overLimit + id else it.overLimit)
            }
            return@launch
        }
        local.update { it.copy(resuming = null, overLimit = it.overLimit - id) }
        onStarted(row.id)
    }

    /** [block]'s value, or null with its error shown on [alias]'s sheet — only while that sheet is the one open. */
    private suspend fun <T> guarded(alias: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        local.update { if (it.alias == alias) it.copy(error = friendly(t)) else it }
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
