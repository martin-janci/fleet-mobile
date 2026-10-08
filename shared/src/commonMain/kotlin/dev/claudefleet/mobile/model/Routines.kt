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
)

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
