package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RoutineActions
import dev.claudefleet.mobile.model.FleetRun
import dev.claudefleet.mobile.model.LoopHealth
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.relativeAgo
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.net.HubCapabilities
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

enum class AutomationTab { Routines, Runs, Agents }

/** One of the agents fleet runs itself (the desktop's `builtInAgents`, 8.4): what it does and what it is doing now. */
data class BuiltInAgent(val id: String, val name: String, val does: String, val state: String)

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
    /** The newest runs across the routines, newest first: an older hub's Runs tab. */
    val runs: List<RunLine> = emptyList(),
    /**
     * Every run on the fleet's behalf, newest first (`runs { list }`, 8.3):
     * tasks, missions, Jev, `claude -p` and routine fires. Null where the hub
     * has no such list, and the tab draws [runs] instead.
     */
    val fleetRuns: List<FleetRun>? = null,
    /** The routine opened from the list; null shows the list. */
    val detail: RoutineDetail? = null,
    /** The hub's `automation_paused`; null until read. */
    val paused: Boolean? = null,
    /** The hub's built-in routines (8.1's loops), read-only; empty from an older hub. */
    val loops: List<LoopHealth> = emptyList(),
    /** What the routines spent today, micro-USD (`routines { budget }`); null from a hub that does not say. */
    val spentToday: Long? = null,
    /** What is being sent: `routine:<id>` or `pause`. */
    val busy: String? = null,
    val notice: String? = null,
    val error: Friendly? = null,
) {
    /** The three agents fleet runs itself (8.4), read-only: Control's operator, the orchestrator and Jev. */
    fun agents(nowSeconds: Long): List<BuiltInAgent> = builtInAgents(loops, fleetRuns.orEmpty(), paused == true, nowSeconds)
}

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
        val fleetRuns: List<FleetRun>? = null,
        val detail: RoutineDetail? = null,
        val paused: Boolean? = null,
        val loops: List<LoopHealth> = emptyList(),
        val spentToday: Long? = null,
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
            fleetRuns = l.fleetRuns,
            detail = l.detail,
            paused = l.paused,
            loops = l.loops,
            spentToday = l.spentToday,
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
            val health = runCatching { actions.health() }.getOrNull()
            // Today's spend, for More's line; a hub without `budget` says nothing and the line names none.
            val budget = if (fleet.capabilities.value.has(HubCapabilities.ROUTINES, "budget")) runCatching { actions.budget() }.getOrNull() else null
            local.update {
                it.copy(
                    routines = routines,
                    paused = health?.automationPaused ?: it.paused,
                    loops = health?.loops ?: it.loops,
                    spentToday = budget?.spentMicros ?: it.spentToday,
                )
            }
            if (fleet.capabilities.value.runs) {
                val all = actions.fleetRuns(RUNS_SHOWN)
                local.update { it.copy(loading = false, fleetRuns = all, runs = emptyList()) }
            } else {
                local.update { it.copy(loading = false, runs = recentRuns(routines), fleetRuns = null) }
            }
            local.value.detail?.routine?.id?.let { readOne(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    /**
     * The Runs list on a hub without `runs` (before 8.3): each routine's own
     * newest runs, merged: [RUNS_PER_ROUTINE] from each of
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

/**
 * The More row's live line (MobileNav): "3 active · $4.10 today", "Paused ·
 * 3 active", "No routines yet". Today's spend only where the hub said it
 * ([spentMicros]); Pause all is the row's own button, not words in the line.
 */
fun automationLine(routines: List<Routine>, paused: Boolean?, spentMicros: Long? = null): String {
    val count = if (routines.isEmpty()) "No routines yet" else "${routines.count { it.enabled }} active"
    val parts = listOfNotNull("Paused".takeIf { paused == true }, count, spentMicros?.let { "${dollars(it)} today" })
    return parts.joinToString(" · ")
}

/**
 * The More row's inline button (MobileNav): Pause all while automation runs,
 * Resume while it stands still; none before the state is read, for a device
 * that may not write `automation.paused`, or while a change is on the wire.
 */
fun automationRowAction(state: AutomationUiState): String? = when {
    !state.canPause || state.paused == null || state.busy != null -> null
    state.paused == true -> "Resume"
    else -> "Pause all"
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

/** A run's status word (8.3's outcomes): Working, Failed, Needs you, Done; nothing to do has none. */
fun fleetRunWord(run: FleetRun): StatusWord? = when (run.outcome) {
    "running" -> StatusWord.WORKING
    "failed" -> StatusWord.FAILED
    "needs_person" -> StatusWord.NEEDS_YOU
    "ok" -> StatusWord.DONE
    else -> null
}

/** Who made a run, in words: "Control" for the operator, else the kind ("Morning brief"). */
fun fleetRunKind(kind: String): String = when (kind) {
    "operator" -> "Control"
    "jev" -> "Jev"
    "" -> "Run"
    else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** A run's title: its owner (a mission's, routine's or session's name), else its kind. */
fun fleetRunTitle(run: FleetRun): String = run.owner.takeIf { it.isNotBlank() && it != run.kind } ?: fleetRunKind(run.kind)

/**
 * A run's line: what kind it is, what it came to when that is more than its
 * word ("nothing to do", the error, the summary), when, how long, what it
 * cost: "Mission · failed: no worktree · 2 h ago · 4 min · $0.42".
 */
fun fleetRunLine(run: FleetRun, nowSeconds: Long): String {
    val outcome = when (run.outcome) {
        "failed" -> run.error?.takeIf { it.isNotBlank() }?.let { "failed: $it" }
        "nothing_to_do" -> "nothing to do"
        else -> null
    } ?: run.summary?.takeIf { it.isNotBlank() }
    val took = run.durationMs?.takeIf { it > 0 }?.let { ms ->
        val secs = ms / 1000
        when {
            secs < 60 -> "under a minute"
            secs < 3600 -> "${secs / 60} min"
            else -> "${secs / 3600} h ${(secs % 3600) / 60} min"
        }
    }
    return listOfNotNull(
        fleetRunKind(run.kind).takeIf { fleetRunTitle(run) != it },
        outcome,
        relativeAgo(run.startedAt, nowSeconds),
        took,
        run.costMicros?.takeIf { it > 0 }?.let(::dollars),
    ).joinToString(" · ")
}

/** "3 min", "2 h", "4 d": a span in the desktop's Automation words. */
private fun span(secs: Long): String = when {
    secs < 60 -> "${maxOf(0, secs)} s"
    secs < 3_600 -> "${secs / 60} min"
    secs < 86_400 -> "${secs / 3_600} h"
    else -> "${secs / 86_400} d"
}

/**
 * A built-in routine's line, the desktop's `loopLine` (8.4): "last run 3 min
 * ago · next in 5 min", "paused", "failed 2 min ago: …", "not run yet here".
 */
fun loopLine(loop: LoopHealth, nowSeconds: Long, paused: Boolean): String {
    val parts = mutableListOf<String>()
    val stopped = paused && loop.pausable
    if (stopped) parts += "paused"
    val last = loop.lastRunAt
    if (last != null) {
        val ago = "${span(nowSeconds - last)} ago"
        parts += if (loop.result == "error") "failed $ago" + (loop.lastError?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") else "last run $ago"
    } else {
        parts += "not run yet here"
    }
    val next = loop.nextRunAt
    if (next != null && !stopped) parts += if (next > nowSeconds) "next in ${span(next - nowSeconds)}" else "due now"
    return parts.joinToString(" · ")
}

/** A built-in routine's word: Failed after an error, Paused under Pause all, else none (it is the fleet's own). */
fun loopWord(loop: LoopHealth, paused: Boolean): StatusWord? = when {
    paused && loop.pausable -> StatusWord.PAUSED
    loop.result == "error" -> StatusWord.FAILED
    else -> null
}

/**
 * The agents fleet runs itself, as the desktop's Automation lists them (8.4):
 * the operator by its last run, the orchestrator by the missions loop, Jev by
 * what it proposed among the runs read.
 */
fun builtInAgents(loops: List<LoopHealth>, runs: List<FleetRun>, paused: Boolean, nowSeconds: Long): List<BuiltInAgent> {
    val lastOperator = runs.filter { it.kind == "operator" }.maxByOrNull { it.startedAt }
    val missions = loops.firstOrNull { it.name == "missions" }
    val jev = runs.count { it.kind == "jev" }
    return listOf(
        BuiltInAgent(
            "operator",
            "Operator",
            "The fleet agent you talk to in Control; hands work to sessions and missions.",
            lastOperator?.let { "last ran ${span(nowSeconds - it.startedAt)} ago" } ?: "no run lately",
        ),
        BuiltInAgent(
            "orchestrator",
            "Orchestrator",
            "Runs missions: plans, dispatches tasks, applies the brakes.",
            missions?.let { loopLine(it, nowSeconds, paused) } ?: "not reported",
        ),
        BuiltInAgent(
            "jev",
            "Jev",
            "Proposes the small calls a person would otherwise make; a person confirms.",
            if (jev == 0) "no proposals lately" else "$jev proposal${if (jev == 1) "" else "s"} among the last runs",
        ),
    )
}
