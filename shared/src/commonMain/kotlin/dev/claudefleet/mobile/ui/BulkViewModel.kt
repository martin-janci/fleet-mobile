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

/**
 * One session's outcome in a bulk action: done, skipped (with why) or failed
 * (with why). [retryable] is false for a skip — the hub would refuse it again,
 * so the outcome offers no Retry on it.
 */
data class BulkOutcome(
    val sessionId: Long,
    val name: String,
    val ok: Boolean,
    val reason: String? = null,
    val retryable: Boolean = !ok,
)

/** What a bulk action did, so its outcome can name it and Retry can do it again. */
sealed interface BulkAction {
    data class Send(val text: String) : BulkAction
    data object Kill : BulkAction
    /** Off the work board (MobileSessionsTools, r09 B6). */
    data object Archive : BulkAction
}

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
    /** The action [outcome] reports on; Retry repeats it. */
    val action: BulkAction? = null,
    /** The hub can archive and this pairing may: the bar shows Archive. */
    val canArchive: Boolean = false,
) {
    val active: Boolean get() = selecting || selected.isNotEmpty()

    /** The outcome rows Retry can try again: failures, not skips. */
    val retryable: List<BulkOutcome> get() = outcome.orEmpty().filter { !it.ok && it.retryable }
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
    /** Archives one session (`archive` on the work link); offered only while the hub says it can. */
    private val archiveOne: (suspend (Long) -> Unit)? = null,
) {
    private data class Local(
        val selected: Set<Long> = emptySet(),
        val running: Boolean = false,
        val outcome: List<BulkOutcome>? = null,
        val selecting: Boolean = false,
        val action: BulkAction? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<BulkUiState> = combine(local, fleet.sessions, fleet.capabilities) { l, rows, caps ->
        // A session that left the fleet leaves the selection with it.
        val live = l.selected.filterTo(LinkedHashSet()) { id -> rows.any { it.id == id } }
        BulkUiState(
            enabled = canWrite,
            selected = live,
            running = l.running,
            outcome = l.outcome,
            killable = rows.count { it.id in live && killRefusal(it) == null },
            selecting = l.selecting,
            action = l.action,
            canArchive = canWrite && archiveOne != null && caps.archiveSession,
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

    /** Pick every one of [ids] (the rows on screen): the selection bar's *Select all*. */
    fun selectAll(ids: Collection<Long>) {
        if (!canWrite) return
        local.update { it.copy(selected = it.selected + ids, selecting = true) }
    }

    fun clear() {
        local.update { it.copy(selected = emptySet(), selecting = false) }
    }

    fun dismissOutcome() {
        local.update { it.copy(outcome = null) }
    }

    /** Send [text] to every picked session. */
    fun send(text: String): Job = run(BulkAction.Send(text), local.value.selected, merge = false)

    /** Kill every picked session the hub would let go; skip and name the rest. */
    fun kill(): Job = run(BulkAction.Kill, local.value.selected, merge = false)

    /** Archive every picked session; the controller is skipped. */
    fun archive(): Job = run(BulkAction.Archive, local.value.selected, merge = false)

    /**
     * The last action again, for one session the outcome says did not go
     * through. Its line in the outcome is replaced by the new answer; the
     * rest stay as they were (MobileSessionsTools: "Retry, alone or all at once").
     */
    fun retry(sessionId: Long): Job = retryWhere { it.sessionId == sessionId }

    /** The last action again, for every session it did not reach. */
    fun retryFailed(): Job = retryWhere { true }

    private fun retryWhere(pick: (BulkOutcome) -> Boolean): Job {
        val l = local.value
        val ids = l.outcome.orEmpty().filter { !it.ok && it.retryable && pick(it) }.mapTo(LinkedHashSet()) { it.sessionId }
        val action = l.action ?: return scope.launch { }
        return run(action, ids, merge = true)
    }

    private fun run(action: BulkAction, ids: Set<Long>, merge: Boolean): Job = scope.launch {
        val l = local.value
        if (!canWrite || l.running || ids.isEmpty()) return@launch
        if (action == BulkAction.Archive && (archiveOne == null || !fleet.capabilities.value.archiveSession)) return@launch
        fun skip(row: SessionRow): String? = when (action) {
            BulkAction.Kill -> killRefusal(row)
            BulkAction.Archive -> if (row.isController) "the fleet's controller is not archived" else null
            is BulkAction.Send -> null
        }
        suspend fun call(row: SessionRow) {
            when (action) {
                is BulkAction.Send -> actions.sendPrompt(row.id, action.text)
                BulkAction.Kill -> actions.kill(row.id)
                BulkAction.Archive -> (archiveOne ?: throw IllegalStateException("this hub cannot archive"))(row.id)
            }
        }
        val rows = fleet.sessions.value
        val picked = ids.mapNotNull { id -> rows.firstOrNull { it.id == id } }
        local.update { it.copy(running = true, outcome = if (merge) it.outcome else null, action = action) }
        val outcome = picked.map { row ->
            val refusal = skip(row)
            if (refusal != null) {
                BulkOutcome(row.id, row.displayName, ok = false, reason = refusal, retryable = false)
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
        local.update { cur ->
            val merged = if (merge) cur.outcome.orEmpty().map { o -> outcome.firstOrNull { it.sessionId == o.sessionId } ?: o } else outcome
            cur.copy(running = false, outcome = merged, selected = cur.selected - done, selecting = false)
        }
    }
}

/** Why the hub would refuse to kill [row], or null — the same rule as a session's own *Kill now*. */
internal fun killRefusal(row: SessionRow): String? = when {
    row.isController -> "the fleet's controller is not killed from here"
    row.kind == "external" -> "an external session cannot be killed by the fleet"
    else -> null
}
