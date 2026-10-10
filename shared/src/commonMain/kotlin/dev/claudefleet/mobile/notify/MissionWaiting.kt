package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.ui.missionAskWords
import dev.claudefleet.mobile.ui.missionWaits

/**
 * A mission that waits on a person (contract 15's `waiting_on`, gap plan
 * G5.7) as the notifier's reason: not a session's, so it never reaches the
 * Inbox's session list through here; its Needs you kind and the fleet's
 * Needs you row decide whether it is posted.
 */
const val MISSION_WAITING_REASON: String = "mission_waiting"

/**
 * One "a mission waits for you" notification (board MobileControl: "Mission
 * waits for you to sign the autonomy grant"). It says what, never answers it:
 * a tap opens the app, where the grant is reviewed and signed.
 */
data class MissionWaitAlert(val missionId: Long, val title: String, val text: String)

/** What makes a wait news: why, and since when. A new wait on the same mission is news again. */
private fun waitKey(m: Mission): String = m.waitingOn?.let { "${it.reason}:${it.since}" }.orEmpty()

/**
 * What a look at the missions tells the notifier: each mission now waiting
 * on a person ([missionWaits]) for a wait not in [seen] (mission id → wait,
 * as of the last look). With no last look, this one is the baseline and
 * nothing is news, as for routines: a wait older than the watcher is in the
 * Inbox already. The answer carries the waits as of [missions].
 */
fun missionWaitAlerts(seen: Map<Long, String>?, missions: List<Mission>): Pair<List<MissionWaitAlert>, Map<Long, String>> {
    val waiting = missionWaits(missions)
    val now = waiting.associate { it.id to waitKey(it) }
    if (seen == null) return emptyList<MissionWaitAlert>() to now
    val alerts = waiting.filter { seen[it.id] != now[it.id] }.map { m ->
        MissionWaitAlert(
            missionId = m.id,
            title = m.name.ifBlank { "Mission ${m.id}" },
            text = "Mission waits for you to ${missionAskWords(m.waitingOn!!)}",
        )
    }
    return alerts to now
}

/** The waits the background check last saw, in [Prefs]; null before the first look. */
private const val MISSION_WAIT_SEEN: String = "mission_wait_seen"
private const val MISSION_SEEN_HEADER = "v1"

fun Prefs.readMissionSeen(): Map<Long, String>? =
    getStringList(MISSION_WAIT_SEEN).takeIf { MISSION_SEEN_HEADER in it }?.mapNotNull { line ->
        val id = line.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
        id to line.substringAfter('=')
    }?.toMap()

fun Prefs.writeMissionSeen(seen: Map<Long, String>) {
    putStringList(MISSION_WAIT_SEEN, listOf(MISSION_SEEN_HEADER) + seen.map { (id, key) -> "$id=$key" })
}
