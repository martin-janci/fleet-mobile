package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.FleetRun
import dev.claudefleet.mobile.model.HubHealth
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.RoutineBudget
import dev.claudefleet.mobile.model.RoutineDetail
import dev.claudefleet.mobile.model.RoutineRun

/**
 * The calls the Automation sheet may make (claude-fleet redesign 8.5 and
 * 8.1): routines, their runs and their switch, and Pause all.
 *
 * Its own interface, as [MissionActions] is, so the sheet gets only the calls
 * it draws. Pause all has no tool of its own on the hub: it is the
 * `automation.paused` setting, written with `set_setting` and read back from
 * `fleet_health`.
 */
interface RoutineActions {
    suspend fun routines(): List<Routine>

    suspend fun routine(routineId: Long): RoutineDetail

    suspend fun runs(routineId: Long, limit: Int): List<RoutineRun>

    /**
     * Every run the fleet made on this person's behalf (`runs { list }`,
     * claude-fleet 8.3): tasks, missions, Jev, `claude -p` and routine fires.
     * The default is a hub without the tool, which the caller never asks.
     */
    suspend fun fleetRuns(limit: Int): List<FleetRun> = emptyList()

    suspend fun setEnabled(routineId: Long, enabled: Boolean): Routine

    /** Whether the hub's automation stands still now. */
    suspend fun paused(): Boolean

    /**
     * Pause all and the built-in routines in one `fleet_health` read (8.1).
     * The default asks [paused] alone: a hub that reports no loops.
     */
    suspend fun health(): HubHealth = HubHealth(automationPaused = paused())

    /** Pause all automation, or let it run again. */
    suspend fun setPaused(paused: Boolean)

    /**
     * What the routines spent today (`routines { budget }`). The default is
     * a hub without the action: no reading, and the line names no spend.
     */
    suspend fun budget(): RoutineBudget? = null
}

/** [RoutineActions] against the paired hub, through [AppSession.withClient]. */
class HubRoutineActions(private val session: AppSession) : RoutineActions {
    override suspend fun routines(): List<Routine> = session.withClient { it.routines() }

    override suspend fun routine(routineId: Long): RoutineDetail = session.withClient { it.routine(routineId) }

    override suspend fun runs(routineId: Long, limit: Int): List<RoutineRun> =
        session.withClient { it.routineRuns(routineId, limit) }

    override suspend fun fleetRuns(limit: Int): List<FleetRun> = session.withClient { it.runs(limit).runs }

    override suspend fun setEnabled(routineId: Long, enabled: Boolean): Routine =
        session.withClient { it.setRoutineEnabled(routineId, enabled) }

    override suspend fun paused(): Boolean = session.withClient { it.fleetHealth().automationPaused }

    override suspend fun health(): HubHealth = session.withClient { it.fleetHealth() }

    override suspend fun setPaused(paused: Boolean) {
        session.withClient { it.setSetting(AUTOMATION_PAUSED, paused.toString()) }
    }

    override suspend fun budget(): RoutineBudget? = session.withClient { it.routineBudget() }

    private companion object {
        const val AUTOMATION_PAUSED = "automation.paused"
    }
}
