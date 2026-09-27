package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.data.WorkViewActions
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.TaskLink
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_TASK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.existingSessionId
import dev.claudefleet.mobile.net.isUnknownAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One session under the task, as the task screen draws it. */
data class TaskSessionLine(
    val link: TaskLink,
    /** A live session to open: the link names one. */
    val canOpen: Boolean,
)

data class WorkTaskUiState(
    val taskId: String,
    val detail: TaskDetail? = null,
    val loading: Boolean = false,
    /** The hub answers this task as unknown: gone, or not this token's to see. */
    val notFound: Boolean = false,
    /** Every session: active (primary first), suggested, then ended newest first — the hub's order. */
    val sessions: List<TaskSessionLine> = emptyList(),
    /** A live session is on the task: Start here and Continue would make a second. */
    val liveSessionId: Long? = null,
    /** **Start here**: the New session form in ticket mode — a key, no live session, a write token, `work_link start`. */
    val canStart: Boolean = false,
    /** **Continue** the last conversation: the resume plan allows `last`, a write token, `work_link resume`. */
    val canContinue: Boolean = false,
    val resumeHosts: List<String> = emptyList(),
    val resumeHost: String? = null,
    val busy: Boolean = false,
    val error: Friendly? = null,
)

/**
 * The task screen (claude-fleet M14.4, read part): one task from
 * `work { task }`, with every session and why it is linked.
 *
 * The actions are the app's existing ones, gated the way the Tickets sheet
 * gates them ([TicketsViewModel]): **Open** a live session; **Continue**
 * past work with `work_link resume` (mode `last`, a host picker from the
 * resume plan, a jump when the hub answers `E_EXISTS`); **Start here**
 * through the New session form in ticket mode, which is its own confirm.
 * *Place in group…* and the link decisions are the Work view's edits, not
 * here.
 */
class WorkTaskViewModel(
    private val taskId: String,
    private val fleet: FleetState,
    private val viewActions: WorkViewActions,
    private val workActions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val onOpenSession: (Long) -> Unit,
    /** Open the New session form in ticket mode for this key. */
    private val onStartHere: (String) -> Unit,
    private val debounceMs: Long = WorkTreeViewModel.SESSION_DEBOUNCE_MS,
) {
    private data class Local(
        val detail: TaskDetail? = null,
        val plan: ResumePlan? = null,
        val resumeHost: String? = null,
        val loading: Boolean = false,
        val notFound: Boolean = false,
        val busy: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())
    private var pending: Job? = null
    private var generation = 0

    val state: StateFlow<WorkTaskUiState> =
        combine(fleet.capabilities, fleet.sessions, local) { caps, rows, l -> assemble(caps, rows, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.sessions.value, local.value))

    init {
        scope.launch {
            fleet.sessionChanges.collect { id ->
                val shown = local.value.detail?.task?.sessions.orEmpty().any { it.sessionId == id }
                val row = fleet.sessions.value.firstOrNull { it.id == id }
                val key = local.value.detail?.task?.key
                val onThis = row != null && key != null && (row.work?.key == key || row.workSuggested?.key == key)
                if (id == ALL_SESSIONS_CHANGED || shown || onThis) {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(debounceMs)
                        load()
                    }
                }
            }
        }
    }

    /** Read the task, then — when it has a key and nothing is live on it — whether there is past work to continue. */
    fun load(): Job? {
        val caps = fleet.capabilities.value
        if (!caps.lists(WORK, WORK_TASK)) return null
        val gen = ++generation
        local.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try {
                val detail = viewActions.task(taskId)
                if (gen != generation) return@launch
                local.update { it.copy(detail = detail, loading = false, notFound = false) }
                val key = detail.task.key
                val live = detail.task.sessions.any { it.state == LinkState.ACTIVE && it.sessionId != null }
                if (key != null && !live && caps.has(WORK, RESUME_PLAN)) {
                    val plan = readPlan(key)
                    if (gen != generation) return@launch
                    local.update { it.copy(plan = plan, resumeHost = it.resumeHost ?: plan?.hostAlias) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (gen != generation) return@launch
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, WORK_TASK)
                val gone = t is HubError.Tool && t.code == "E_NOTFOUND"
                local.update {
                    it.copy(loading = false, notFound = gone, detail = if (gone) null else it.detail, error = if (gone) null else friendlyWork(t))
                }
            }
        }
    }

    /** A plan that cannot be read offers no Continue; it never fails the screen. */
    private suspend fun readPlan(key: String): ResumePlan? = try {
        workActions.resumePlan(key)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, RESUME_PLAN)
        null
    }

    fun selectResumeHost(alias: String) {
        local.update { it.copy(resumeHost = alias) }
    }

    /** **Open** one session under the task. */
    fun open(sessionId: Long) {
        if (state.value.sessions.none { it.canOpen && it.link.sessionId == sessionId }) return
        onOpenSession(sessionId)
    }

    /** **Start here**: the New session form, in ticket mode. */
    fun startHere() {
        val s = state.value
        if (!s.canStart) return
        onStartHere(s.detail?.task?.key ?: return)
    }

    /** **Continue** the last conversation on the chosen host — `work_link resume`, as the Tickets sheet does. */
    fun continueWork(): Job? {
        val s = state.value
        val key = s.detail?.task?.key ?: return null
        if (!s.canContinue || local.value.busy) return null
        val host = local.value.resumeHost
        local.update { it.copy(busy = true, error = null) }
        return scope.launch {
            try {
                val row = workActions.resume(key, host)
                local.update { it.copy(busy = false) }
                onOpenSession(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val jump = (t as? HubError.Tool)?.existingSessionId()
                if (jump != null) {
                    local.update { it.copy(busy = false) }
                    onOpenSession(jump)
                } else {
                    if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, RESUME)
                    local.update { it.copy(busy = false, error = friendlyWork(t)) }
                }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private fun assemble(caps: HubCapabilities, rows: List<SessionRow>, l: Local): WorkTaskUiState {
        val detail = l.detail
        val task = detail?.task
        val lines = task?.sessions.orEmpty().map { link ->
            TaskSessionLine(link, canOpen = link.sessionId != null && link.state != LinkState.ENDED && link.state != LinkState.REJECTED)
        }
        // Live by the task's own links, or by the fleet's rows: a session
        // linked since the task was read still means "open that one".
        val alive = rows.mapTo(HashSet()) { it.id }
        val live = task?.sessions.orEmpty()
            .firstOrNull { it.state == LinkState.ACTIVE && it.sessionId != null && (rows.isEmpty() || it.sessionId in alive) }
            ?.sessionId
            ?: task?.key?.let { key -> rows.firstOrNull { it.work?.key == key }?.id }
        val key = task?.key
        val plan = l.plan?.takeIf { live == null }
        return WorkTaskUiState(
            taskId = taskId,
            detail = detail,
            loading = l.loading,
            notFound = l.notFound,
            sessions = lines,
            liveSessionId = live,
            canStart = task != null && live == null && key != null && canWrite && caps.has(WORK_LINK, START),
            canContinue = key != null && plan?.canResumeLast == true && canWrite && caps.has(WORK_LINK, RESUME),
            resumeHosts = plan?.let { p -> (listOfNotNull(p.hostAlias) + p.hosts).distinct() }.orEmpty(),
            resumeHost = l.resumeHost,
            busy = l.busy,
            error = l.error,
        )
    }

    private companion object {
        const val RESUME_PLAN = "resume_plan"
        const val START = "start"
        const val RESUME = "resume"
    }
}
