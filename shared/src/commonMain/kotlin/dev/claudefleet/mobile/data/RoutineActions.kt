package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Routine
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

    suspend fun setEnabled(routineId: Long, enabled: Boolean): Routine

    /** Whether the hub's automation stands still now. */
    suspend fun paused(): Boolean

    /** Pause all automation, or let it run again. */
    suspend fun setPaused(paused: Boolean)
}

/** [RoutineActions] against the paired hub, through [AppSession.withClient]. */
class HubRoutineActions(private val session: AppSession) : RoutineActions {
    override suspend fun routines(): List<Routine> = session.withClient { it.routines() }

    override suspend fun routine(routineId: Long): RoutineDetail = session.withClient { it.routine(routineId) }

    override suspend fun runs(routineId: Long, limit: Int): List<RoutineRun> =
        session.withClient { it.routineRuns(routineId, limit) }

    override suspend fun setEnabled(routineId: Long, enabled: Boolean): Routine =
        session.withClient { it.setRoutineEnabled(routineId, enabled) }

    override suspend fun paused(): Boolean = session.withClient { it.fleetHealth().automationPaused }

    override suspend fun setPaused(paused: Boolean) {
        session.withClient { it.setSetting(AUTOMATION_PAUSED, paused.toString()) }
    }

    private companion object {
        const val AUTOMATION_PAUSED = "automation.paused"
    }
}
