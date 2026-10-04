package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionDetailsActions
import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A session's Details sheet: its row's facts, its timeline, what shares its worktree, and its tasks. */
data class SessionDetailsUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val session: SessionRow? = null,
    /** Each section exists only where the hub has its tool. */
    val historyAvailable: Boolean = false,
    val relatedAvailable: Boolean = false,
    val tasksAvailable: Boolean = false,
    val events: List<SessionEvent> = emptyList(),
    val filter: Set<EventCategory> = emptySet(),
    val related: List<SessionRow> = emptyList(),
    /** Tasks this session asked for or works on. */
    val tasks: List<FleetTask> = emptyList(),
    /** This token may cancel a task: the hub lists `cancel_task` and the pairing may write. */
    val canCancel: Boolean = false,
    /** The task whose cancel is in flight. */
    val cancelling: Long? = null,
    val error: Friendly? = null,
    val nowSeconds: Long = 0,
) {
    val shownEvents: List<SessionEvent> get() = filterEvents(events, filter)
}

/**
 * Reads the three Details sections when the sheet opens, in parallel, each
 * only where the hub has its tool; a failed section says so on the banner
 * and leaves the others drawn. While the sheet is open a row change for this
 * session re-reads the timeline, so a new event shows up without a pull.
 */
@OptIn(FlowPreview::class)
internal class SessionDetailsViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: SessionDetailsActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val clock: () -> Long,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val events: List<SessionEvent> = emptyList(),
        val filter: Set<EventCategory> = emptySet(),
        val related: List<SessionRow> = emptyList(),
        val tasks: List<FleetTask> = emptyList(),
        val cancelling: Long? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<SessionDetailsUiState> =
        combine(local, fleet.sessions, fleet.capabilities) { l, rows, caps ->
            SessionDetailsUiState(
                open = l.open,
                loading = l.loading,
                session = rows.firstOrNull { it.id == sessionId },
                historyAvailable = caps.sessionHistory,
                relatedAvailable = caps.relatedSessions,
                tasksAvailable = caps.tasks,
                events = l.events,
                filter = l.filter,
                related = l.related,
                tasks = l.tasks,
                canCancel = canWrite && caps.cancelTask,
                cancelling = l.cancelling,
                error = l.error,
                nowSeconds = clock(),
            )
        }.stateIn(scope, SharingStarted.Eagerly, SessionDetailsUiState())

    init {
        scope.launch {
            fleet.sessionChanges
                .filter { (it == sessionId || it == ALL_SESSIONS_CHANGED) && local.value.open }
                .debounce(REREAD_DEBOUNCE_MS)
                .collect { readHistory() }
        }
    }

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, error = null) }
        load()
    }

    fun close() {
        local.update { it.copy(open = false) }
    }

    fun reload(): Job = scope.launch { load() }

    /** A chip: on adds its category to the filter, off removes it; none on shows everything. */
    fun toggle(category: EventCategory) {
        local.update { it.copy(filter = if (category in it.filter) it.filter - category else it.filter + category) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Cancel a queued or running task. The worker session keeps running. */
    fun cancel(taskId: Long): Job = scope.launch {
        if (!state.value.canCancel || local.value.cancelling != null) return@launch
        local.update { it.copy(cancelling = taskId, error = null) }
        try {
            actions.cancelTask(taskId)
            readTasks()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(cancelling = null) }
        }
    }

    private suspend fun load() {
        local.update { it.copy(loading = true) }
        val caps = fleet.capabilities.value
        val reads = listOfNotNull(
            if (caps.sessionHistory) scope.async { readHistory() } else null,
            if (caps.relatedSessions) scope.async { readRelated() } else null,
            if (caps.tasks) scope.async { readTasks() } else null,
        )
        reads.forEach { it.await() }
        local.update { it.copy(loading = false) }
    }

    private suspend fun readHistory() = section {
        val events = actions.history(sessionId)
        local.update { it.copy(events = events) }
    }

    private suspend fun readRelated() = section {
        // The hub answers the session itself among those sharing its worktree.
        val related = actions.related(sessionId).filter { it.id != sessionId }
        local.update { it.copy(related = related) }
    }

    private suspend fun readTasks() = section {
        val mine = actions.tasks().filter { it.requesterSessionId == sessionId || it.workerSessionId == sessionId }
        local.update { it.copy(tasks = mine) }
    }

    /** One section's read: a failure goes to the banner and leaves the other sections alone. */
    private suspend fun section(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    private companion object {
        const val REREAD_DEBOUNCE_MS = 1_000L
    }
}
