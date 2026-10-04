package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
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

/** One session's outcome in a bulk action: done, skipped (with why) or failed (with why). */
data class BulkOutcome(val sessionId: Long, val name: String, val ok: Boolean, val reason: String? = null)

/** The sessions list's multi-select: which rows are picked, and the last bulk action's outcome. */
data class BulkUiState(
    /** False for a readonly pairing: nothing here would be allowed. */
    val enabled: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val running: Boolean = false,
    /** What the last bulk action did, per session, until dismissed. */
    val outcome: List<BulkOutcome>? = null,
    /** Which picked sessions Kill now would really kill; the rest it skips and says why. */
    val killable: Int = 0,
    /** Select mode was asked for (the list's *Select*), with or without anything picked yet. */
    val selecting: Boolean = false,
) {
    val active: Boolean get() = selecting || selected.isNotEmpty()
}

/**
 * The desktop's bulk select on the phone: pick sessions (a long press starts,
 * a tap then toggles), then send them one prompt or kill them. One call per
 * session — `send_prompt`, `kill_session` — in the order picked, each one's
 * failure reported against its name rather than stopping the rest. Kill skips
 * what the hub would refuse (the controller, an external session) and says so.
 */
class BulkViewModel(
    private val fleet: FleetState,
    private val actions: SessionActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val selected: Set<Long> = emptySet(),
        val running: Boolean = false,
        val outcome: List<BulkOutcome>? = null,
        val selecting: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<BulkUiState> = combine(local, fleet.sessions) { l, rows ->
        // A session that left the fleet leaves the selection with it.
        val live = l.selected.filterTo(LinkedHashSet()) { id -> rows.any { it.id == id } }
        BulkUiState(
            enabled = canWrite,
            selected = live,
            running = l.running,
            outcome = l.outcome,
            killable = rows.count { it.id in live && killRefusal(it) == null },
            selecting = l.selecting,
        )
    }.stateIn(scope, SharingStarted.Eagerly, BulkUiState(enabled = canWrite))

    fun toggle(sessionId: Long) {
        if (!canWrite) return
        local.update { it.copy(selected = if (sessionId in it.selected) it.selected - sessionId else it.selected + sessionId) }
    }

    /**
     * Select mode with nothing picked yet: the visible way in, beside the
     * long press, so a person who never long-presses still finds it.
     */
    fun start() {
        if (!canWrite) return
        local.update { it.copy(selecting = true) }
    }

    fun clear() {
        local.update { it.copy(selected = emptySet(), selecting = false) }
    }

    fun dismissOutcome() {
        local.update { it.copy(outcome = null) }
    }

    /** Send [text] to every picked session. */
    fun send(text: String): Job = run(skip = { null }) { actions.sendPrompt(it.id, text) }

    /** Kill every picked session the hub would let go; skip and name the rest. */
    fun kill(): Job = run(skip = ::killRefusal) { actions.kill(it.id) }

    private fun run(skip: (SessionRow) -> String?, call: suspend (SessionRow) -> Unit): Job = scope.launch {
        val l = local.value
        if (!canWrite || l.running || l.selected.isEmpty()) return@launch
        val rows = fleet.sessions.value
        val picked = l.selected.mapNotNull { id -> rows.firstOrNull { it.id == id } }
        local.update { it.copy(running = true, outcome = null) }
        val outcome = picked.map { row ->
            val refusal = skip(row)
            if (refusal != null) {
                BulkOutcome(row.id, row.displayName, ok = false, reason = refusal)
            } else {
                try {
                    call(row)
                    BulkOutcome(row.id, row.displayName, ok = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    BulkOutcome(row.id, row.displayName, ok = false, reason = friendly(t).title)
                }
            }
        }
        // What went through leaves the selection; what did not stays picked to try again.
        val done = outcome.filter { it.ok }.mapTo(HashSet()) { it.sessionId }
        local.update { it.copy(running = false, outcome = outcome, selected = it.selected - done, selecting = false) }
    }
}

/** Why the hub would refuse to kill [row], or null — the same rule as a session's own *Kill now*. */
internal fun killRefusal(row: SessionRow): String? = when {
    row.isController -> "the fleet's controller is not killed from here"
    row.kind == "external" -> "an external session cannot be killed by the fleet"
    else -> null
}
