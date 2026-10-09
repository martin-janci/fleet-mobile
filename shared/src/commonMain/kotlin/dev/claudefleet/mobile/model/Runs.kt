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
