package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One run on the fleet's behalf (claude-fleet redesign 8.3, `runs { list }`):
 * a dispatched task, a mission's step or brake, a Jev decision, one of
 * fleet's own `claude -p` runs (planner, summary, triage, …) or a routine
 * fire. Mirrors `fleet_core::store::RunRow`; optional fields are absent on the
 * wire, never null.
 */
@Serializable
data class FleetRun(
    /** `<source>:<rowid>`, stable for the row's life. */
    val id: String,
    /** task | orchestration | jev | aux | routine */
    val source: String = "",
    /** operator | task | mission | jev | planner | summary | … | routine */
    val kind: String = "",
    /** A mission's or routine's name, a session's name, `operator`, a Jev use case, `summary`. */
    val owner: String = "",
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("ended_at") val endedAt: Long? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    /** ok | failed | needs_person | nothing_to_do | running */
    val outcome: String = "",
    val error: String? = null,
    @SerialName("cost_micros") val costMicros: Long? = null,
    val model: String? = null,
    val host: String? = null,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("mission_id") val missionId: Long? = null,
    /** The sessions it ran in or acted on, the worker first. */
    @SerialName("session_ids") val sessionIds: List<Long> = emptyList(),
    val summary: String? = null,
    @SerialName("routine_id") val routineId: Long? = null,
)

/** `runs { list }`: one page, newest first, and how many match in all. */
@Serializable
data class RunsPage(
    val runs: List<FleetRun> = emptyList(),
    val total: Long = 0,
)

/**
 * One step of a run's trace (claude-fleet `runs { trace }`, migration 194):
 * the trigger, checks, placement, start, prompt, each tool Claude finished,
 * the settle and who judged the outcome. Mirrors
 * `fleet_core::store::RunTraceRow`; optional fields are absent, never null.
 */
@Serializable
data class RunTraceStep(
    val seq: Long,
    val attempt: Long = 1,
    @SerialName("parent_ref") val parentRef: String? = null,
    /** Unix seconds. */
    val at: Long = 0,
    /** trigger | checks | placement | spawn | prompt | working | settle | outcome */
    val phase: String = "",
    /** trigger | check | placement | spawn | prompt | tool_call | decision | settle | error | … */
    val kind: String = "",
    /** scheduler | hub | claude | jev | rule | person */
    val actor: String = "",
    val summary: String = "",
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("cost_micros") val costMicros: Long? = null,
    @SerialName("decision_id") val decisionId: Long? = null,
)

/** `runs { trace }`: a run's steps after `after_seq`, oldest first. */
@Serializable
data class RunTrace(
    @SerialName("run_id") val runId: String = "",
    val steps: List<RunTraceStep> = emptyList(),
    /** More steps follow the last one: read on from its seq. */
    val more: Boolean = false,
)

/** The phases a run passes through, in order, with their words. */
val RUN_PHASES: List<Pair<String, String>> = listOf(
    "trigger" to "Trigger",
    "checks" to "Checks",
    "placement" to "Placement",
    "spawn" to "Session started",
    "prompt" to "Prompt delivered",
    "working" to "Working",
    "settle" to "Settle",
    "outcome" to "Outcome",
)

/** Routine fires and agent jobs keep a trace; the other runs do not. */
fun runHasTrace(run: FleetRun): Boolean = run.source == "routine" || run.source == "agent"

/** A phase on the rail: reached, the run's live edge, failed in it, a fire stopped there, or not reached. */
enum class PhaseState { Done, Live, Failed, Skipped, Todo }

data class PhaseCell(val phase: String, val label: String, val state: PhaseState, val meta: String)

/**
 * The phase rail for [steps] of a run that ended [outcome] (the desktop's
 * `phaseRail`): a phase with steps is done, failed when one is an error;
 * while the run runs, `working` is live once the prompt is in, else the
 * phase after the newest reached one; a skipped fire stops at its checks.
 */
fun phaseRail(steps: List<RunTraceStep>, outcome: String, skipped: Boolean = false): List<PhaseCell> {
    val by = steps.groupBy { it.phase }
    val last = RUN_PHASES.indexOfLast { by.containsKey(it.first) }
    val running = outcome == "running" && !skipped
    val liveAt = when {
        !running -> -1
        last >= 0 && RUN_PHASES[last].first in setOf("prompt", "working") -> RUN_PHASES.indexOfFirst { it.first == "working" }
        else -> last + 1
    }
    return RUN_PHASES.mapIndexed { i, (phase, label) ->
        val mine = by[phase].orEmpty()
        var state = when {
            mine.any { it.kind == "error" } -> PhaseState.Failed
            phase == "checks" && skipped -> PhaseState.Skipped
            mine.isNotEmpty() -> PhaseState.Done
            else -> PhaseState.Todo
        }
        if (i == liveAt && state != PhaseState.Failed) state = PhaseState.Live
        val meta = if (phase == "working" && mine.isNotEmpty()) {
            "${mine.size} step${if (mine.size == 1) "" else "s"}${if (running) " so far" else ""}"
        } else {
            mine.lastOrNull()?.summary.orEmpty()
        }
        PhaseCell(phase, label, state, meta)
    }
}

/** A step's time in words: "410 ms", "41 s", "2m 48s"; empty when unknown. */
fun tookWords(ms: Long?): String = when {
    ms == null -> ""
    ms < 1000 -> "$ms ms"
    else -> {
        val s = (ms + 500) / 1000
        if (s < 60) "$s s" else "${s / 60}m ${s % 60}s"
    }
}
