package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RoutineActions
import dev.claudefleet.mobile.model.FleetRun
import dev.claudefleet.mobile.model.LoopHealth
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineExplain
import dev.claudefleet.mobile.model.RoutineMemory
import dev.claudefleet.mobile.model.RoutineNotebook
import dev.claudefleet.mobile.model.RoutineRun
import dev.claudefleet.mobile.model.RunTraceStep
import dev.claudefleet.mobile.model.StorageHealth
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

/**
 * A run opened from the Runs list (claude-fleet's run timeline): the run and
 * the steps its trace holds so far, oldest first.
 */
data class RunDetail(val run: FleetRun, val steps: List<RunTraceStep> = emptyList(), val loaded: Boolean = false)

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
    /** The run opened from the Runs list, with its steps; null shows the list. */
    val runDetail: RunDetail? = null,
    /** The hub keeps run traces (`runs` takes a `run_id`): a routine fire or agent job opens to its steps. */
    val canTrace: Boolean = false,
    /** Why [detail] does or does not fire; null from a hub without `explain`. */
    val explain: RoutineExplain? = null,
    /**
     * [detail]'s notebooks (claude-fleet migration 204); null when it keeps
     * none, or from a hub without routine memory.
     */
    val memory: RoutineMemory? = null,
    /** A person may rewrite or clear [memory]'s notebooks from this device. */
    val canEditMemory: Boolean = false,
    /** The hub's `automation_paused`; null until read. */
    val paused: Boolean? = null,
    /** The hub's built-in routines (8.1's loops), read-only; empty from an older hub. */
    val loops: List<LoopHealth> = emptyList(),
    /** The hub's own disk and backups; null from an older hub. */
    val storage: StorageHealth? = null,
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
        val runDetail: RunDetail? = null,
        val explain: RoutineExplain? = null,
        val memory: RoutineMemory? = null,
        val paused: Boolean? = null,
        val loops: List<LoopHealth> = emptyList(),
        val storage: StorageHealth? = null,
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
            runDetail = l.runDetail,
            canTrace = caps.accepts(HubCapabilities.RUNS, "run_id"),
            explain = l.explain,
            memory = l.memory,
            canEditMemory = canWrite && caps.routines && l.memory?.mayChange == true,
            paused = l.paused,
            loops = l.loops,
            storage = l.storage,
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
        local.update { it.copy(open = false, detail = null, memory = null, runDetail = null, notice = null) }
    }

    /** The More row's line wants the routines without the sheet open. */
    fun refresh(): Job = scope.launch { read() }

    fun show(tab: AutomationTab) {
        local.update { it.copy(tab = tab, detail = null, memory = null, runDetail = null) }
    }

    /** Open [run]'s steps (a routine fire or an agent job, on a hub that keeps traces). */
    fun openRun(run: FleetRun): Job = scope.launch {
        local.update { it.copy(runDetail = RunDetail(run), notice = null, error = null) }
        readRun()
    }

    /** Back from a run's steps to the Runs list. */
    fun closeRun() {
        local.update { it.copy(runDetail = null) }
    }

    /**
     * Read on from the open run's last step: the sheet calls it every few
     * seconds while the run runs. The run's row is refreshed from the list
     * so it settles when the run does.
     */
    fun refreshRun(): Job = scope.launch {
        val open = local.value.runDetail ?: return@launch
        try {
            val fresh = actions.fleetRuns(RUNS_SHOWN)
            local.update { l ->
                val run = fresh.firstOrNull { it.id == open.run.id }
                l.copy(fleetRuns = fresh, runDetail = l.runDetail?.let { d -> if (run != null && d.run.id == run.id) d.copy(run = run) else d })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The steps below still move; the row catches up on the next read.
        }
        readRun()
    }

    private suspend fun readRun() {
        var held = local.value.runDetail ?: return
        try {
            for (page in 0 until TRACE_PAGES) {
                val after = held.steps.lastOrNull()?.seq
                val trace = actions.runTrace(held.run.id, after)
                val fresh = trace.steps.filter { after == null || it.seq > after }
                held = held.copy(steps = held.steps + fresh, loaded = true)
                if (!trace.more || trace.steps.isEmpty()) break
            }
            local.update { l ->
                val cur = l.runDetail
                if (cur != null && cur.run.id == held.run.id) l.copy(runDetail = held.copy(run = cur.run)) else l
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { l -> l.copy(error = friendly(t), runDetail = l.runDetail?.copy(loaded = true)) }
        }
    }

    fun select(routineId: Long): Job = scope.launch {
        local.update { it.copy(notice = null, error = null) }
        readOne(routineId)
    }

    fun back() {
        local.update { it.copy(detail = null, memory = null, notice = null) }
    }

    /**
     * Rewrite the open routine's [notebook] (`routine` | `project`) with
     * [text]; `""` clears it. The card shows the hub's answer, never the
     * text as sent. [onDone] hears null once the hub took it, else why it
     * did not: the editor stays open with the person's text until then.
     */
    fun saveMemory(notebook: String, text: String, onDone: (Friendly?) -> Unit = {}): Job = scope.launch {
        val id = local.value.memory?.routineId
        if (id == null || !state.value.canEditMemory) {
            onDone(Friendly("Not saved", "This device may not change the routine's notebooks.", isError = true))
            return@launch
        }
        if (local.value.busy != null) {
            onDone(Friendly("Not saved yet", "Another change is still being sent. Try again in a moment.", isError = true))
            return@launch
        }
        local.update { it.copy(busy = "memory:$id", error = null, notice = null) }
        try {
            val saved = actions.setMemory(id, notebook, text)
            local.update {
                val notice = if (text.isEmpty()) "Notebook cleared." else "Notebook saved."
                if (it.detail?.routine?.id == id) it.copy(busy = null, memory = saved, notice = notice) else it.copy(busy = null)
            }
            onDone(null)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val why = friendly(t)
            local.update { it.copy(busy = null, error = why) }
            onDone(why)
        }
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
                    // A failed health read keeps the last; a hub that answers
                    // without it (an older one) has none.
                    storage = if (health != null) health.storage else it.storage,
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
            local.update { it.copy(loading = false, detail = detail, explain = null, memory = null).withRoutine(detail.routine) }
            // The why is a second read: an older hub refuses the action, and
            // the routine still shows without it.
            val why = try {
                actions.explain(routineId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            local.update { if (it.detail?.routine?.id == routineId) it.copy(explain = why) else it }
            // Its notebooks, only for a routine whose row names them: an
            // older hub sends no `memory`, and is never asked the action. A
            // hub that names them serves the action, so a failed read says
            // why rather than hiding the card as if the routine kept none.
            if (detail.routine.memory != null) {
                try {
                    val memory = actions.memory(routineId)
                    local.update { if (it.detail?.routine?.id == routineId) it.copy(memory = memory) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    local.update { if (it.detail?.routine?.id == routineId) it.copy(error = friendly(t)) else it }
                }
            }
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
        const val TRACE_PAGES = 10
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
        "event" -> "When ${eventLabel(r.event)}" + (r.eventHost?.takeIf { it.isNotBlank() }?.let { " on $it" } ?: "")
        "manual" -> "Run now only"
        else -> listOfNotNull(
            r.cron?.takeIf { it.isNotBlank() },
            r.nextRunAt?.let { relativeWithin(it, nowSeconds) }?.let { "next in $it" },
        ).joinToString(" · ").ifEmpty { "On a schedule" }
    }
    return listOfNotNull(what, "skips the next run".takeIf { r.skipNext }, r.hostAlias.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** An event a routine waits on, in words: a session's, a pull request's, or the fleet's (a host, an account, a mission).
 * The desktop's `eventLabel` (src/lib/routines.ts) in lower case: one routine reads the same on both (review w30-5). */
fun eventLabel(event: String?): String = when (event) {
    "turn_done" -> "a session is done with a turn"
    "stop_failure" -> "a session's turn fails"
    "stuck" -> "a session is stuck"
    "lost" -> "a session is lost"
    "task_done" -> "a session is done with its task"
    "task_failed" -> "a session is failed at its task"
    "session_restore_failed" -> "a session is not restored after a reboot"
    "workspace_repair_failed" -> "a session is left with a broken workspace"
    "needs_you" -> "a session is waiting for you"
    "auto_restored" -> "a session is brought back automatically"
    "auto_restore_failed" -> "an automatic restore attempt fails"
    "account_switched" -> "a session is moved to another account"
    "account_switch_failed" -> "an account switch fails"
    "session_moved" -> "a session is moved to another host"
    "pr_opened" -> "a pull request is opened"
    "pr_review" -> "a pull request gets a review"
    "pr_ci_failed" -> "a pull request's checks fail"
    "pr_ci_passed" -> "a pull request's checks pass"
    "pr_merged" -> "a pull request is merged"
    "host_offline" -> "a host goes offline"
    "host_online" -> "a host is back online"
    "host_low" -> "a host runs low on disk or memory"
    "account_limited" -> "an account hits its limit"
    "account_available" -> "an account is under its limit again"
    "login_signed_out" -> "a Claude login is signed out"
    "mission_done" -> "a mission completes"
    "mission_failed" -> "a mission fails"
    "ticket_done" -> "a ticket is marked done"
    "check_passed" -> "an AI check passes"
    "check_failed" -> "an AI check fails"
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
    if (next != null && !stopped) {
        parts += when {
            loopStalled(loop, nowSeconds) -> "overdue by ${span(nowSeconds - next)}; it may have stopped"
            next > nowSeconds -> "next in ${span(next - nowSeconds)}"
            else -> "due now"
        }
    }
    return parts.joinToString(" · ")
}

/** One warning about the hub's own storage: a title and its line. */
data class StorageWarning(val title: String, val line: String)

/**
 * The hub's disk and backup warnings, as the desktop's Attention items say
 * them (`storageAttentionItems`): one per flag the hub raised.
 */
fun storageWarnings(s: StorageHealth?, nowSeconds: Long): List<StorageWarning> {
    if (s == null) return emptyList()
    val out = mutableListOf<StorageWarning>()
    if (s.lowDisk) {
        val free = s.freeBytes?.let { gb(it) } ?: "little"
        val of = s.totalBytes?.let { " of ${gb(it)}" } ?: ""
        out += StorageWarning("Hub disk almost full", "$free free$of; its database stops saving when the disk fills.")
    }
    if (s.backupStale) {
        val last = s.lastBackupAt
        val whenSaid = if (last != null) "The newest backup is ${span(nowSeconds - last)} old" else "The hub has no backup"
        out += StorageWarning("Hub backup stale", "$whenSaid; it takes one daily. Check its log and its backups folder.")
    }
    return out
}

/** Bytes as "1.5 GB", rounded to a tenth. */
private fun gb(bytes: Long): String {
    val tenths = (bytes * 10 + 512L * 1024 * 1024) / (1024L * 1024 * 1024)
    return "${tenths / 10}.${tenths % 10} GB"
}

/** Least time past its due run before a loop reads stalled, whatever its period. */
const val LOOP_STALL_GRACE_SECONDS = 600L

/**
 * Whether a loop missed its next run by more than a whole period (and at
 * least [LOOP_STALL_GRACE_SECONDS]), the desktop's `loopStalled`: a loop whose
 * task died still shows its last good run, and read "due now" forever.
 */
fun loopStalled(loop: LoopHealth, nowSeconds: Long): Boolean {
    val last = loop.lastRunAt ?: return false
    val next = loop.nextRunAt ?: return false
    val period = maxOf(0L, next - last)
    return nowSeconds - next > maxOf(period, LOOP_STALL_GRACE_SECONDS)
}

/**
 * A built-in routine's word: Failed after an error or once it stopped running
 * ([loopStalled]), Paused under Pause all, else none (it is the fleet's own).
 */
fun loopWord(loop: LoopHealth, paused: Boolean, nowSeconds: Long): StatusWord? = when {
    paused && loop.pausable -> StatusWord.PAUSED
    loop.result == "error" -> StatusWord.FAILED
    loopStalled(loop, nowSeconds) -> StatusWord.FAILED
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

/** The checks the Why card lists: the ones that stop a fire, then the caveats. */
fun whyLines(why: RoutineExplain): List<String> =
    (why.checks.filter { it.state == "block" } + why.checks.filter { it.state == "warn" })
        .map { "${if (it.state == "block") "Stops it" else "Note"}: ${it.detail}" }

/** A notebook's heading: the routine's own, or its project's. */
fun notebookTitle(scope: String): String = if (scope == "project") "The project's notebook" else "This routine's notebook"

/** Who wrote a notebook last, and when: "Last written by run 42 · 3 min ago"; "Empty" when nothing was. */
fun notebookLine(n: RoutineNotebook, nowSeconds: Long): String {
    if (n.updatedAt == 0L) return "Empty"
    val who = when (n.updatedBy) {
        "run" -> n.updatedRunId?.let { "run $it" } ?: "a run"
        "fleet" -> "the fleet"
        else -> "a person"
    }
    return listOfNotNull("Last written by $who", relativeAgo(n.updatedAt, nowSeconds)).joinToString(" · ")
}

/** What a routine's notebooks are, in words: "Its own notebook · notes each run". */
fun memoryLine(m: RoutineMemory): String = listOfNotNull(
    when (m.memory) {
        "routine" -> "Its own notebook"
        "project" -> "The project's notebook"
        "both" -> "Its own and the project's notebooks"
        else -> null
    },
    "notes each run".takeIf { m.memoryAuto },
).joinToString(" · ")
