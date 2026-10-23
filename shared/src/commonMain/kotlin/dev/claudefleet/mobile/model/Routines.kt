package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One routine, as the hub's `routines { list }` answers it (claude-fleet
 * redesign 8.5, `store/routines.rs`): a saved prompt that starts a session on
 * a cron line, a session event or Run now. The phone mirrors what it draws;
 * the rest passes by under `ignoreUnknownKeys`.
 */
@Serializable
data class Routine(
    val id: Long,
    val name: String = "",
    val enabled: Boolean = false,
    /** cron | event | manual */
    val trigger: String = "",
    val cron: String? = null,
    val event: String? = null,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    @SerialName("skip_next") val skipNext: Boolean = false,
    /** Why the scheduler turned it off (a budget, a failing streak); null when a person did. */
    @SerialName("paused_reason") val pausedReason: String? = null,
    /** An event routine fires only on this host (hub migration 201). */
    @SerialName("event_host") val eventHost: String? = null,
    /**
     * The notebooks its runs read and rewrite (hub migration 204): `routine`,
     * `project` or `both`; null when it keeps none, or from a hub without
     * routine memory.
     */
    val memory: String? = null,
    /** The fleet notes each finished run in the notebook. */
    @SerialName("memory_auto") val memoryAuto: Boolean = false,
)

/**
 * One notebook (claude-fleet `store::RoutineMemoryRow`): the routine's own
 * (`scope` `routine`) or its project's, shared by the project's routines in
 * one org (`project`).
 */
@Serializable
data class RoutineNotebook(
    /** routine | project */
    val scope: String = "",
    val body: String = "",
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** person | run | fleet */
    @SerialName("updated_by") val updatedBy: String = "",
    @SerialName("updated_run_id") val updatedRunId: Long? = null,
)

/**
 * `routines { memory }` and `set_memory`'s answer (claude-fleet
 * `routines::memory::RoutineMemory`): the notebooks a routine uses as they
 * are now. A notebook nothing wrote yet is absent.
 */
@Serializable
data class RoutineMemory(
    @SerialName("routine_id") val routineId: Long = 0,
    val memory: String? = null,
    @SerialName("memory_auto") val memoryAuto: Boolean = false,
    val routine: RoutineNotebook? = null,
    val project: RoutineNotebook? = null,
    @SerialName("max_chars") val maxChars: Int = 4000,
    @SerialName("may_change") val mayChange: Boolean = false,
) {
    /** The notebooks it uses, its own first, each with what was written (an empty one when nothing was). */
    fun notebooks(): List<RoutineNotebook> = buildList {
        if (memory == "routine" || memory == "both") add(routine ?: RoutineNotebook(scope = "routine"))
        if (memory == "project" || memory == "both") add(project ?: RoutineNotebook(scope = "project"))
    }
}

/**
 * A notebook's length as the hub counts it against [RoutineMemory.maxChars]:
 * Unicode code points (Rust's `chars().count()`), not UTF-16 units, so an
 * emoji counts once — the same count the desktop's editor shows.
 */
fun notebookChars(text: String): Int = text.length - text.indices.count { i ->
    i > 0 && text[i].isLowSurrogate() && text[i - 1].isHighSurrogate()
}

/** One run of a routine (`routine_runs`). */
@Serializable
data class RoutineRun(
    val id: Long,
    @SerialName("routine_id") val routineId: Long,
    /** cron | event | run_now */
    val trigger: String = "",
    /** running | done | failed | skipped */
    val state: String = "",
    val reason: String? = null,
    @SerialName("session_id") val sessionId: Long? = null,
    @SerialName("cost_micros") val costMicros: Long = 0,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
)

/** `routines { get }`: the routine, its last runs, and whether this person may change it. */
@Serializable
data class RoutineDetail(
    val routine: Routine,
    val runs: List<RoutineRun> = emptyList(),
    @SerialName("may_change") val mayChange: Boolean = false,
)

/**
 * `routines { budget }` (claude-fleet 8.6, `FleetBudget`): what every
 * routine's runs cost since the UTC day began, and the fleet's daily budget
 * when one is set — More's "3 active · $4.10 today".
 */
@Serializable
data class RoutineBudget(
    @SerialName("spent_micros") val spentMicros: Long = 0,
    @SerialName("budget_micros") val budgetMicros: Long? = null,
    /** The UTC day's start, unix seconds. */
    val since: Long = 0,
)

/** `routines { failing }`: a routine whose newest run failed, with that run (claude-fleet 8.6). */
@Serializable
data class FailingRoutine(
    val routine: Routine,
    val run: RoutineRun,
    @SerialName("may_change") val mayChange: Boolean = false,
)

/**
 * One check a fire passes or fails (`routines { explain }`, claude-fleet
 * migration 185): `enabled`, `automation`, `scheduler`, `schedule` or
 * `event`, `overlap`, `day_budget`, `fleet_budget`, `account`, `host`.
 */
@Serializable
data class RoutineCheck(
    val name: String = "",
    /** ok | warn | block */
    val state: String = "",
    val detail: String = "",
)

/**
 * `routines { explain }`: why a routine does or does not fire, in one line,
 * and its checks in the scheduler's order. The event list and the decision
 * trace pass by under `ignoreUnknownKeys`: the desktop's Debug tab draws them.
 */
@Serializable
data class RoutineExplain(
    val summary: String = "",
    /** ok | warn | block: the worst check. */
    val state: String = "ok",
    val checks: List<RoutineCheck> = emptyList(),
)
