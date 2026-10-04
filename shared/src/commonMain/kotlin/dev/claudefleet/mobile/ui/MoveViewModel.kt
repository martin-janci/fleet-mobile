package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MoveActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MoveOutcome
import dev.claudefleet.mobile.model.MovePreview
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

data class MoveUiState(
    val available: Boolean = false,
    val open: Boolean = false,
    /** Hosts it could go to: reachable, shown, not the one it is on. */
    val targets: List<HostRow> = emptyList(),
    val target: String? = null,
    val keepSource: Boolean = false,
    val whenIdle: Boolean = false,
    val preview: MovePreview? = null,
    val busy: Boolean = false,
    /** A move waiting for the session to go idle: where to, and by when. */
    val waiting: MoveOutcome.Waiting? = null,
    val warnings: List<String> = emptyList(),
    val error: Friendly? = null,
)

/**
 * The desktop's Transfer sheet on the phone: pick a host, see what the move
 * would carry (a dry run — uncommitted and unpushed work, git-ignored files,
 * the Claude state, and what is already at the target), then move now or
 * when the session goes idle. A moved session opens on its new host.
 */
class MoveViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: MoveActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val target: String? = null,
        val keepSource: Boolean = false,
        val whenIdle: Boolean = false,
        val preview: MovePreview? = null,
        val busy: Boolean = false,
        val waiting: MoveOutcome.Waiting? = null,
        val warnings: List<String> = emptyList(),
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<MoveUiState> = combine(local, fleet.hosts, fleet.sessions, fleet.capabilities) { l, hosts, rows, caps ->
        val from = rows.firstOrNull { it.id == sessionId }?.hostAlias
        MoveUiState(
            available = canWrite && caps.moveSession,
            open = l.open,
            targets = hosts.filter { it.reachable && !it.hidden && it.alias != from },
            target = l.target,
            keepSource = l.keepSource,
            whenIdle = l.whenIdle,
            preview = l.preview,
            busy = l.busy,
            waiting = l.waiting,
            warnings = l.warnings,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MoveUiState())

    fun open() {
        local.update { it.copy(open = true, error = null) }
    }

    fun close() {
        local.update { it.copy(open = false) }
    }

    fun selectTarget(alias: String): Job {
        local.update { it.copy(target = alias, preview = null, error = null) }
        return preview()
    }

    fun setKeepSource(on: Boolean) {
        local.update { it.copy(keepSource = on) }
    }

    fun setWhenIdle(on: Boolean) {
        local.update { it.copy(whenIdle = on) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** The dry run for the chosen host: nothing changes. */
    fun preview(): Job = scope.launch {
        val l = local.value
        val target = l.target ?: return@launch
        if (!allowed()) return@launch
        local.update { it.copy(busy = true) }
        guarded {
            val out = actions.preview(sessionId, target, l.keepSource)
            local.update { if (it.target == target && out is MoveOutcome.Preview) it.copy(preview = out.preview) else it }
        }
        local.update { it.copy(busy = false) }
    }

    /** Move now, or arm the move for when the session goes idle; [onMoved] opens the moved session. */
    fun move(onMoved: (Long) -> Unit): Job = scope.launch {
        val l = local.value
        val target = l.target ?: return@launch
        if (!allowed() || l.busy) return@launch
        local.update { it.copy(busy = true, error = null) }
        guarded {
            when (val out = actions.move(sessionId, target, l.keepSource, l.whenIdle)) {
                is MoveOutcome.Moved -> {
                    local.update { it.copy(open = false, warnings = out.warnings) }
                    onMoved(out.target.id)
                }
                is MoveOutcome.Waiting -> local.update { it.copy(waiting = out) }
                else -> Unit
            }
        }
        local.update { it.copy(busy = false) }
    }

    /** End a move that is waiting for the session to go idle. */
    fun cancelWait(): Job = scope.launch {
        val waiting = local.value.waiting ?: return@launch
        local.update { it.copy(busy = true) }
        guarded {
            actions.cancelWait(sessionId, waiting.toHost)
            local.update { it.copy(waiting = null) }
        }
        local.update { it.copy(busy = false) }
    }

    /** [MoveUiState.available], read from the live sources: `state` trails them by a dispatch. */
    private fun allowed(): Boolean = canWrite && fleet.capabilities.value.moveSession

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }
}
