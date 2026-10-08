package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.StartOutcome

/**
 * The missions calls the Missions sheet may make (claude-fleet orchestration).
 *
 * Its own interface, next to [WorkActions], for the reason [VersionActions]
 * is: a screen gets only the calls it draws, and every one goes through
 * [AppSession.withClient]. The reads are `work`, readonly on the hub; the
 * rest are `work_link`, which the hub hides from a readonly token and takes
 * from a person's device only — the sheet offers them only when the
 * credential can write **and** [FleetState.capabilities] lists the action.
 */
interface MissionActions {
    suspend fun missions(): List<Mission>

    suspend fun mission(missionId: Long): MissionDetail

    /** Take the next steps, or only the one [step] names. */
    suspend fun start(missionId: Long, step: String? = null): StartOutcome

    /** Apply or dismiss a card; a question is answered with [note]. */
    suspend fun decideCard(cardId: Long, ok: Boolean, note: String? = null): MissionCard

    /** Pause or resume one mission. */
    suspend fun setState(missionId: Long, state: String, expectedVersion: Long): Mission

    /** Pause every mission this person may change; the ids that were paused. */
    suspend fun pauseAll(): List<Long>
}

/** [MissionActions] against the paired hub, through [AppSession.withClient]. */
class HubMissionActions(private val session: AppSession) : MissionActions {
    override suspend fun missions(): List<Mission> = session.withClient { it.workMissions() }

    override suspend fun mission(missionId: Long): MissionDetail = session.withClient { it.workMission(missionId) }

    override suspend fun start(missionId: Long, step: String?): StartOutcome =
        session.withClient { it.startMission(missionId, step) }

    override suspend fun decideCard(cardId: Long, ok: Boolean, note: String?): MissionCard =
        session.withClient { it.decideMissionCard(cardId, ok, note) }

    override suspend fun setState(missionId: Long, state: String, expectedVersion: Long): Mission =
        session.withClient { it.setMissionState(missionId, state, expectedVersion) }

    override suspend fun pauseAll(): List<Long> = session.withClient { it.pauseAllMissions() }
}
