package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkViewActions
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.SessionTaskLink
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_SESSION_TASKS
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
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

/** The kinds of link a session's *Tasks* section lists, in its order. */
enum class SessionTaskKind(val title: String) {
    Primary("Primary"),
    Secondary("Also on"),
    Suggested("Suggested"),
    Past("Past"),
    Rejected("Not this"),
}

data class SessionTaskGroup(val kind: SessionTaskKind, val links: List<SessionTaskLink>)

data class SessionTasksUiState(
    /** The hub's schema lists `work { session_tasks }`: the section is offered. */
    val available: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val groups: List<SessionTaskGroup> = emptyList(),
    /** Links this session has — for the menu's *Tasks (n)*, once read. */
    val count: Int? = null,
    val error: Friendly? = null,
) {
    val isEmpty: Boolean get() = count == 0
}

/**
 * A session screen's read-only *Tasks* section (claude-fleet M14.4): every
 * link of the session from `work { session_tasks }` — primary, secondary,
 * suggested, past, and the ones a person rejected. A task opens the task
 * screen. *Make primary*, *Remove* and *Add task…* are the Work view's edits
 * and not here.
 *
 * Read when the sheet opens, and again when the hub reports this session's
 * row changed (`session:updated`), which is how a link change reaches a
 * phone — bound or not.
 */
class SessionTasksViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: WorkViewActions,
    private val scope: CoroutineScope,
    private val onOpenTask: (String) -> Unit,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val tasks: SessionTasks? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())
    private var generation = 0

    val state: StateFlow<SessionTasksUiState> =
        combine(fleet.capabilities, local) { caps, l -> assemble(caps, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, local.value))

    init {
        scope.launch {
            fleet.sessionChanges.collect { id ->
                if (local.value.open && (id == sessionId || id == ALL_SESSIONS_CHANGED)) load()
            }
        }
    }

    fun open(): Job? {
        if (!state.value.available) return null
        local.update { it.copy(open = true) }
        return load()
    }

    fun close() {
        local.update { it.copy(open = false, error = null) }
    }

    fun openTask(taskId: String) {
        close()
        onOpenTask(taskId)
    }

    fun load(): Job? {
        if (!fleet.capabilities.value.lists(WORK, WORK_SESSION_TASKS)) return null
        val gen = ++generation
        local.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try {
                val tasks = actions.sessionTasks(sessionId)
                if (gen == generation) local.update { it.copy(tasks = tasks, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (gen != generation) return@launch
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, WORK_SESSION_TASKS)
                local.update { it.copy(loading = false, error = friendlyWork(t)) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun assemble(caps: HubCapabilities, l: Local): SessionTasksUiState {
        if (!caps.lists(WORK, WORK_SESSION_TASKS)) return SessionTasksUiState()
        val links = l.tasks?.links.orEmpty()
        return SessionTasksUiState(
            available = true,
            open = l.open,
            loading = l.loading,
            groups = SessionTaskKind.entries.mapNotNull { kind ->
                links.filter { kindOf(it) == kind }.takeIf { it.isNotEmpty() }?.let { SessionTaskGroup(kind, it) }
            },
            count = l.tasks?.links?.size,
            error = l.error,
        )
    }

    companion object {
        /** Which list a link belongs in. A state this build does not know is shown with the past, never as live. */
        fun kindOf(link: SessionTaskLink): SessionTaskKind = when (link.link.state) {
            LinkState.ACTIVE -> if (link.link.primary) SessionTaskKind.Primary else SessionTaskKind.Secondary
            LinkState.SUGGESTED -> SessionTaskKind.Suggested
            LinkState.REJECTED -> SessionTaskKind.Rejected
            else -> SessionTaskKind.Past
        }
    }
}
