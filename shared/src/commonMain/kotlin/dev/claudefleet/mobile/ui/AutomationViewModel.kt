package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RoutineActions
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.ui.kit.StatusWord
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

enum class AutomationTab { Routines, Runs }

/** One row of the Runs list: the run and the routine it belongs to. */
data class RunLine(val run: RoutineRun, val routine: String)

data class AutomationUiState(
    /** The hub serves `routines` to this token. */
    val available: Boolean = false,
    /** A person may turn a routine on or off. */
    val canToggle: Boolean = false,
    /** A person may write `automation.paused` (the hub still refuses an untrusted device). */
    val canPause: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val tab: AutomationTab = AutomationTab.Routines,
    val routines: List<Routine> = emptyList(),
    /** The newest runs across the routines, newest first. */
    val runs: List<RunLine> = emptyList(),
    /** The routine opened from the list; null shows the list. */
    val detail: RoutineDetail? = null,
    /** The hub's `automation_paused`; null until read. */
    val paused: Boolean? = null,
    /** What is being sent: `routine:<id>` or `pause`. */
    val busy: String? = null,
    val notice: String? = null,
    val error: Friendly? = null,
)

/**
 * Automation on the phone (redesign 8.9): the routines with their switch, the
 * runs they made, and Pause all. Writing a routine stays on the desktop; the
 * phone turns one on or off and pauses everything.
 *
 * Read only where the hub serves `routines` (8.5); an older hub, or a
 * readonly token the hub does not serve it to, never gets the call.
 */
class AutomationViewModel(
    private val fleet: FleetState,
    private val actions: RoutineActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val tab: AutomationTab = AutomationTab.Routines,
        val routines: List<Routine> = emptyList(),
        val runs: List<RunLine> = emptyList(),
        val detail: RoutineDetail? = null,
        val paused: Boolean? = null,
        val busy: String? = null,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AutomationUiState> = combine(local, fleet.capabilities) { l, caps ->
        AutomationUiState(
            available = caps.routines,
            canToggle = canWrite && caps.routines,
            canPause = canWrite && caps.setSetting,
            open = l.open,
            loading = l.loading,
            tab = l.tab,
            routines = l.routines,
            runs = l.runs,
            detail = l.detail,
            paused = l.paused,
            busy = l.busy,
            notice = l.notice,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AutomationUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, detail = null, notice = null, error = null) }
        read()
    }

    fun close() {
        local.update { it.copy(open = false, detail = null, notice = null) }
    }

    /** The More row's line wants the routines without the sheet open. */
    fun refresh(): Job = scope.launch { read() }

    fun show(tab: AutomationTab) {
        local.update { it.copy(tab = tab, detail = null) }
    }

    fun select(routineId: Long): Job = scope.launch {
        local.update { it.copy(notice = null, error = null) }
        readOne(routineId)
    }

    fun back() {
        local.update { it.copy(detail = null, notice = null) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Turn [routine] on or off. The switch moves at once and takes the hub's
     * answer; a refusal puts it back.
     */
    fun toggle(routine: Routine): Job = scope.launch {
        if (!state.value.canToggle || local.value.busy != null) return@launch
        val on = !routine.enabled
        local.update { it.copy(busy = "routine:${routine.id}", error = null, notice = null).withRoutine(routine.copy(enabled = on)) }
        try {
            val saved = actions.setEnabled(routine.id, on)
            local.update { it.copy(busy = null).withRoutine(saved) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)).withRoutine(routine) }
        }
    }

    /** Pause all automation ([paused] true) or let it run again. */
    fun setPaused(paused: Boolean): Job = scope.launch {
        if (!state.value.canPause || local.value.busy != null) return@launch
        local.update { it.copy(busy = PAUSE, error = null, notice = null) }
        try {
            actions.setPaused(paused)
            local.update {
                it.copy(busy = null, paused = paused, notice = if (paused) "Paused all automation." else "Automation runs again.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    private fun Local.withRoutine(r: Routine): Local = copy(
        routines = routines.map { if (it.id == r.id) r else it },
        detail = detail?.let { d -> if (d.routine.id == r.id) d.copy(routine = r) else d },
    )

    private suspend fun read() {
        if (!state.value.available) return
        local.update { it.copy(loading = true) }
        try {
            val routines = actions.routines()
            val paused = runCatching { actions.paused() }.getOrNull()
            local.update { it.copy(routines = routines, paused = paused ?: it.paused) }
            local.update { it.copy(loading = false, runs = recentRuns(routines)) }
            local.value.detail?.routine?.id?.let { readOne(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    /**
     * The Runs list. The hub has no fleet-wide runs read yet (8.3), so it is
     * each routine's own newest runs, merged: [RUNS_PER_ROUTINE] from each of
     * the first [ROUTINES_READ], newest first.
     */
    private suspend fun recentRuns(routines: List<Routine>): List<RunLine> =
        routines.take(ROUTINES_READ).flatMap { r ->
            try {
                actions.runs(r.id, RUNS_PER_ROUTINE).map { RunLine(it, r.name) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }.sortedByDescending { it.run.startedAt }.take(RUNS_SHOWN)

    private suspend fun readOne(routineId: Long) {
        local.update { it.copy(loading = true) }
        try {
            val detail = actions.routine(routineId)
            local.update { it.copy(loading = false, detail = detail).withRoutine(detail.routine) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    private companion object {
        const val PAUSE = "pause"
        const val ROUTINES_READ = 20
        const val RUNS_PER_ROUTINE = 10
        const val RUNS_SHOWN = 50
    }
}

/** The More row: "3 of 5 routines on", "Paused · 3 of 5 routines on", "No routines yet". */
fun automationLine(routines: List<Routine>, paused: Boolean?): String {
    val on = routines.count { it.enabled }
    val count = when {
        routines.isEmpty() -> "No routines yet"
        else -> "$on of ${routines.size} routine${if (routines.size == 1) "" else "s"} on"
    }
    return if (paused == true) "Paused · $count" else "$count · Pause all"
}

/** A routine's line: when it runs next, or why it does not. */
fun routineLine(r: Routine, nowSeconds: Long): String {
    if (!r.enabled) {
        return listOfNotNull("Off", r.pausedReason?.takeIf { it.isNotBlank() }?.let { "turned off: $it" }).joinToString(" · ")
    }
    val what = when (r.trigger) {
        "event" -> "When ${eventLabel(r.event)}"
        "manual" -> "Run now only"
        else -> listOfNotNull(
            r.cron?.takeIf { it.isNotBlank() },
            r.nextRunAt?.let { relativeWithin(it, nowSeconds) }?.let { "next in $it" },
        ).joinToString(" · ").ifEmpty { "On a schedule" }
    }
    return listOfNotNull(what, "skips the next run".takeIf { r.skipNext }, r.hostAlias.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** A session event a routine waits on, in words. */
fun eventLabel(event: String?): String = when (event) {
    "turn_done" -> "a turn ends"
    "stop_failure" -> "a session fails"
    "stuck" -> "a session is stuck"
    "lost" -> "a session is lost"
    "task_done" -> "a task is done"
    "task_failed" -> "a task fails"
    "session_restore_failed" -> "a restore fails"
    "workspace_repair_failed" -> "a repair fails"
    null, "" -> "an event"
    else -> event.replace('_', ' ')
}

/** A run's status word: Working while it runs, Failed, Done; a skipped run has none. */
fun runWord(run: RoutineRun): StatusWord? = when (run.state) {
    "running" -> StatusWord.WORKING
    "failed" -> StatusWord.FAILED
    "done" -> StatusWord.DONE
    else -> null
}

/** What a run came to: its own reason when it has one ("Reviewed 4 PRs"), else its state. */
fun runTitle(run: RoutineRun): String = run.reason?.takeIf { it.isNotBlank() } ?: when (run.state) {
    "running" -> "Working"
    "done" -> "Done"
    "failed" -> "Failed"
    "skipped" -> "Skipped"
    else -> run.state.replaceFirstChar { it.uppercase() }
}

/** When it started, how long it took, what it cost: "2 h ago · 4 min · $0.42". */
fun runLine(run: RoutineRun, nowSeconds: Long): String {
    val took = run.finishedAt?.let { end -> relativeTime(run.startedAt, end)?.takeIf { it != "just now" } ?: "under a minute" }
    return listOfNotNull(
        relativeAgo(run.startedAt, nowSeconds),
        took,
        run.costMicros.takeIf { it > 0 }?.let(::dollars),
        "run now".takeIf { run.trigger == "run_now" },
    ).joinToString(" · ")
}
