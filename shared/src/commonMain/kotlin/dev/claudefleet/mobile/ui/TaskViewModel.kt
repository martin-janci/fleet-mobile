package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgSource
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.existingSessionId
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.utcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TaskUiState(
    val taskId: String,
    val loading: Boolean = false,
    val detail: TaskDetail? = null,
    /** Live and confirmed, the primary first. */
    val active: List<WorkTaskLink> = emptyList(),
    /** Guesses nobody has decided: never counted as the task's sessions. */
    val suggested: List<WorkTaskLink> = emptyList(),
    /** Ended (newest first) and rejected — never drawn as active. */
    val past: List<WorkTaskLink> = emptyList(),
    /** "Acme · from its tracker", or null without an org. */
    val orgLine: String? = null,
    /** "ABC · from the tracker (ABC)". */
    val groupLine: String = "",
    /** The group is the tracker's: a placement here is local only, never a tracker edit. */
    val trackerControlled: Boolean = false,
    val connected: Boolean = false,
    /** **Place in group…**: a full token, `work_link place`, a connection. */
    val canPlace: Boolean = false,
    /** A person's placement is showing, so it can be cleared back to what is derived. */
    val canClearPlacement: Boolean = false,
    /**
     * **Continue**: resume the last conversation — no live, confirmed link to
     * a live session is on the task, and an *ended* link (never a rejected
     * or suggested one: that session was not on this task) left a
     * conversation that can resume and that no live session still holds.
     */
    val canContinue: Boolean = false,
    /** **Start here**: a fresh session on the key — none is live on it. */
    val canStart: Boolean = false,
    val placeOpen: Boolean = false,
    /**
     * Group labels the sheet offers: the Work tree's `label:` groups (a
     * person's or a rule's), never a derived one — see [placeableGroups].
     */
    val knownGroups: List<String> = emptyList(),
    /** The placement's note, kept by a placement unless the person edits it. */
    val placementNote: String = "",
    val busy: Boolean = false,
    val error: Friendly? = null,
    val conflict: Boolean = false,
    /** The hub does not know this task, or it is not visible to this token. */
    val gone: Boolean = false,
    val stale: String? = null,
) {
    val task get() = detail?.task
}

/**
 * One task and every session it has had — the Work view's detail
 * (`work { task }`). Pushed over My work, or over a session's *Tasks*.
 *
 * Its actions go through the flows that already exist: **Open** is a
 * session screen, **Continue** is `work_link resume` (an `E_EXISTS` jumps to
 * the session it names), **Start here** is the New session form in ticket
 * mode. The one edit is **Place in group…** — `work_link place`, a local
 * placement under the task's `placement_version`, so a placement made on
 * another device meanwhile is refused with `E_CONFLICT` rather than
 * overwritten. A tracker's own values are labelled as the tracker's: fleet
 * never edits a tracker. A placement keeps the placement's note unless the
 * person edited it (the hub clears a note a `place` does not send).
 *
 * Writes run in [callScope], which outlives this screen: backing out of a
 * task while its placement or resume is on the wire does not cancel the
 * request half way. What it answers is delivered back — the re-read, the
 * session to open — only while this screen's [scope] is still alive.
 */
class TaskViewModel(
    private val taskId: String,
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** The groups the Work tree knows, for *Place in group…*. */
    private val knownGroups: () -> List<GroupRef> = { emptyList() },
    private val onOpenSession: (Long) -> Unit = {},
    /** The New session form in ticket mode, for this key. */
    private val onStartHere: (String) -> Unit = {},
    private val clock: () -> Long = { epochSeconds() },
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
    private val refreshDebounceMs: Long = 2_000,
    /** Where writes run: the fleet's scope, not this screen's. */
    private val callScope: CoroutineScope = scope,
) {
    private data class Local(
        val loading: Boolean = false,
        val detail: TaskDetail? = null,
        val asOf: Long? = null,
        val placeOpen: Boolean = false,
        val busy: Boolean = false,
        val error: Friendly? = null,
        val conflict: Boolean = false,
        val gone: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<TaskUiState> =
        combine(fleet.capabilities, fleet.status, fleet.orgs, local) { caps, status, orgs, l -> assemble(caps, status, orgs, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, fleet.orgs.value, local.value))

    init {
        scope.launch {
            // First read, and again on every reconnect.
            fleet.status.map { it.isConnected() }.distinctUntilChanged().collect { if (it) load() }
        }
        scope.launch {
            // Throttled, not debounced, and apart: a `work:changed` is never
            // held behind session rows, and a row whose status merely moved
            // is not a change ([workSignatureChanges]).
            merge(fleet.workChanges.throttleLatest(refreshDebounceMs), fleet.workSignatureChanges().throttleLatest(refreshDebounceMs))
                .collect { if (fleet.status.value.isConnected()) load() }
        }
    }

    fun refresh(): Job = scope.launch { load() }

    private suspend fun load() {
        if (!fleet.capabilities.value.has(WORK, TASK)) return
        local.update { it.copy(loading = true) }
        try {
            val detail = actions.task(taskId)
            local.update { it.copy(loading = false, detail = detail, asOf = clock(), gone = false, error = null, conflict = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, TASK)
            val gone = t is HubError.Tool && t.code == "E_NOTFOUND"
            local.update { it.copy(loading = false, gone = gone, error = if (gone) null else friendlyWork(t)) }
        }
    }

    /** **Open**: a live session of the task. */
    fun openSession(link: WorkTaskLink) {
        val id = link.sessionId ?: return
        if (link.state == LinkState.Ended || link.state == LinkState.Rejected) return
        onOpenSession(id)
    }

    /** **Continue**: resume the last conversation on the task's key. */
    fun continueWork(): Job? {
        val s = state.value
        val key = s.task?.key ?: return null
        if (local.value.busy) return null
        if (!s.canContinue) return null.also { refuseOffline(RESUME) }
        local.update { it.copy(busy = true, error = null, conflict = false) }
        return callScope.launch {
            try {
                val row = actions.resume(key, null)
                local.update { it.copy(busy = false) }
                // Opened only while the task is still showing: a resume that
                // lands after the person left must not yank them into it.
                if (scope.isActive) onOpenSession(row.id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val jump = (t as? HubError.Tool)?.existingSessionId()
                if (jump != null) {
                    local.update { it.copy(busy = false) }
                    if (scope.isActive) onOpenSession(jump)
                } else {
                    if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, RESUME)
                    local.update { it.copy(busy = false, error = friendlyWork(t)) }
                }
            }
        }
    }

    /** **Start here**: the New session form, in ticket mode. */
    fun startHere() {
        val s = state.value
        val key = s.task?.key ?: return
        if (!s.canStart) return
        onStartHere(key)
    }

    fun openPlace() {
        if (state.value.canPlace) local.update { it.copy(placeOpen = true) }
    }

    fun closePlace() {
        local.update { it.copy(placeOpen = false) }
    }

    /**
     * Put the task in [group] (a label; blank clears a person's placement).
     * Sent with the placement's version (`0` = "I expect none"); shown only
     * once the hub has answered, and an `E_CONFLICT` keeps the sheet's choice
     * unsent and says what the hub has now.
     *
     * [note] null keeps the placement's note as it is — the hub clears a note
     * a `place` leaves out, so it is sent back unchanged; a string (blank
     * included) is the person's edit. Clearing the placement drops its note
     * with it.
     */
    fun place(group: String, note: String? = null): Job? {
        val detail = state.value.detail ?: return null
        val task = detail.task
        if (local.value.busy) return null
        if (!state.value.canPlace) return null.also { refuseOffline(PLACE) }
        val label = group.trim()
        val sentNote = if (label.isEmpty()) null else (note ?: detail.placement?.note)?.trim()?.takeIf { it.isNotEmpty() }
        val expected = expectedPlacementVersion(detail)
        local.update { it.copy(busy = true, error = null, conflict = false) }
        return callScope.launch {
            try {
                if (!allowed(fleet.capabilities.value, fleet.status.value, PLACE)) {
                    refuseOffline(PLACE)
                    return@launch
                }
                val answered = actions.place(task.taskId, label, expectedVersion = expected, note = sentNote)
                local.update { l ->
                    val detail = l.detail ?: return@update l
                    // The answer is the task; its session list may be cut to
                    // the tree's size, so the one already read stays.
                    val sessions = answered.sessions.ifEmpty { detail.task.sessions }
                    l.copy(detail = detail.copy(task = answered.copy(sessions = sessions)), placeOpen = false)
                }
                // The re-read belongs to the screen: none once it is gone.
                if (scope.isActive) scope.launch { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, PLACE)
                local.update { it.copy(error = friendlyWorkWrite(t), conflict = t.isConflict()) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    /** Back to the derived group: an empty `group`. */
    fun clearPlacement(): Job? = if (state.value.canClearPlacement) place("") else null

    fun dismissError() {
        local.update { it.copy(error = null, conflict = false) }
    }

    /** A write this token and hub may make, tapped while offline: said, never silently dropped. */
    private fun refuseOffline(action: String) {
        if (canWrite && fleet.capabilities.value.has(WORK_LINK, action) && !fleet.status.value.isConnected()) {
            local.update { it.copy(error = OFFLINE_WRITE, conflict = false) }
        }
    }

    private fun allowed(caps: HubCapabilities, status: ConnectionStatus, action: String): Boolean =
        canWrite && status.isConnected() && caps.has(WORK_LINK, action)

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, orgs: OrgDirectory, l: Local): TaskUiState {
        val connected = status.isConnected()
        val detail = l.detail
        val task = detail?.task
        val links = task?.sessions.orEmpty()
        val active = links.filter { it.state == LinkState.Active }.sortedByDescending { it.primary }
        val suggested = links.filter { it.state == LinkState.Suggested }
        val past = links.filter { it.state == LinkState.Ended || it.state == LinkState.Rejected || it.state == LinkState.Unknown }
            .sortedByDescending { it.endedAt ?: it.decidedAt ?: Long.MIN_VALUE }
        // A live session on the task: a confirmed link whose session is
        // still there. Rejected, suggested and ended links never count.
        val hasLive = active.any { it.sessionId != null } || (task?.counts?.active ?: 0) > 0
        val key = task?.key?.takeIf { it.isNotBlank() }
        val orgLine = task?.orgId?.let { "${orgs.name(it)} · ${orgSourceWords(task.orgSource)}" }
            ?: task?.takeIf { it.orgSource != OrgSource.None }?.let { orgSourceWords(it.orgSource) }
        val group = task?.group
        return TaskUiState(
            taskId = taskId,
            loading = l.loading,
            detail = detail,
            active = active,
            suggested = suggested,
            past = past,
            orgLine = orgLine,
            groupLine = group?.let { "${it.title} · ${groupSourceWords(it)}" }.orEmpty(),
            trackerControlled = group?.source == GroupSource.Tracker,
            connected = connected,
            canPlace = task != null && allowed(caps, status, PLACE),
            canClearPlacement = task != null && group?.isPlacedByHand == true && allowed(caps, status, PLACE),
            canContinue = key != null && !hasLive && past.any { it.isContinuable } && allowed(caps, status, RESUME),
            canStart = key != null && !hasLive && allowed(caps, status, START),
            placeOpen = l.placeOpen,
            knownGroups = placeableGroups(knownGroups()),
            placementNote = detail?.placement?.note.orEmpty(),
            busy = l.busy,
            error = l.error,
            conflict = l.conflict,
            gone = l.gone,
            stale = if (!connected && detail != null) l.asOf?.let { staleLine(it, utcOffset(it)) } else null,
        )
    }

    private companion object {
        /**
         * Something **Continue** can resume: an ended link — the session was
         * on this task and has left it — whose conversation can resume and
         * is not held by a session that is still live (an ended link keeps
         * its `session_id` only while that session lives on, and the hub
         * never resumes a conversation a live session holds).
         */
        val WorkTaskLink.isContinuable: Boolean
            get() = state == LinkState.Ended && resumable && sessionId == null

        /**
         * The version a placement is sent under: the task's
         * `placement_version`, else the placement's own, else `0` ("I expect
         * none").
         */
        fun expectedPlacementVersion(detail: TaskDetail): Long =
            detail.task.placementVersion.takeIf { it != 0L } ?: detail.placement?.version ?: 0L

        const val TASK = "task"
        const val PLACE = "place"
        const val RESUME = "resume"
        const val START = "start"
    }
}
