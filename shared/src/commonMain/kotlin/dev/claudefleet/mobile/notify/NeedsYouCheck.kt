package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where "needs you" notifications go on a platform that posts them from shared code. */
interface AlertPoster {
    fun post(alert: NeedsYouAlert)

    fun withdraw(sessionId: Long)

    /** A routine run failed (the matrix's Routine failed row); a platform that cannot say it says nothing. */
    fun postRoutine(alert: RoutineFailedAlert) {}

    /** A mission waits on a person (gap plan G5.7); a platform that cannot say it says nothing. */
    fun postMission(alert: MissionWaitAlert) {}

    /** The mission no longer waits: its notification comes down (review w21-3). */
    fun withdrawMission(missionId: Long) {}

    /** Quiet hours ended with [count] held sessions still waiting: one summary for them (review w11-12). */
    fun postQuietSummary(count: Int) {}

    /** Every alert up: they belong to another hub (review w16-1). */
    fun withdrawAll() {}
}

/** The seen set the background check and the open app share, in [Prefs]. */
const val NEEDS_YOU_SEEN: String = "needs_you_seen"

/**
 * A line that is always written, so "no look yet" (nothing stored) and "the
 * last look saw no sessions" (only this line) are different answers.
 * [decodeSeen] skips it: it has no `=`.
 */
private const val SEEN_HEADER = "v1"

fun Prefs.readSeen(): Map<Long, String?>? =
    getStringList(NEEDS_YOU_SEEN).takeIf { SEEN_HEADER in it }?.let(::decodeSeen)

fun Prefs.writeSeen(seen: Map<Long, String?>) {
    putStringList(NEEDS_YOU_SEEN, listOf(SEEN_HEADER) + encodeSeen(seen))
}

/** The hub the seen sets in [Prefs] belong to. */
private const val SEEN_HUB: String = "needs_you_seen_hub"

/**
 * Keep the seen sets in [Prefs] (sessions, failed runs, mission waits) only
 * for [hub]: another hub's ids name other sessions, runs and missions, so a
 * set carried over from it would silence their news. With no hub recorded
 * yet (kept since before this was), the sets are taken as [hub]'s. True when
 * they belonged to another hub and were cleared, so that a platform keeping
 * a copy of its own (Android's service) clears that too. A cleared set reads
 * as no look yet: the next look is the new hub's baseline.
 */
fun Prefs.keepSeenForHub(hub: String): Boolean {
    val was = getStringList(SEEN_HUB).firstOrNull()
    if (was == hub) return false
    putStringList(SEEN_HUB, listOf(hub))
    if (was == null) return false
    putStringList(NEEDS_YOU_SEEN, emptyList())
    putStringList(ROUTINE_FAILED_SEEN, emptyList())
    putStringList(MISSION_WAIT_SEEN, emptyList())
    putStringList(QUIET_HELD_PREF, emptyList())
    return true
}

/**
 * One look at the fleet, for a platform that cannot keep watching it: iOS,
 * woken by `BGAppRefreshTask` for about thirty seconds at a time of the
 * system's choosing.
 *
 * The same rules as Android's always-on watcher, applied once:
 * [needsYouAlerts] decides what is news against what the last look saw; with
 * no last look, this one is the baseline and nothing is said. The seen set is
 * written only after posting, so a run cut short is repeated, not half-applied
 * — and posting is idempotent per session, so the repeat is harmless.
 *
 * It never throws anything but cancellation. A 401 has already unpaired the
 * device through [AppSession.withClient]; every other failure — the hub
 * unreachable, slow past [timeout], a Keychain that cannot be read before the
 * first unlock — keeps the last look and says nothing. On iOS an exception
 * escaping to Swift ends the process, so there is no other honest answer.
 */
class NeedsYouCheck(
    private val session: AppSession,
    private val prefs: Prefs,
    private val poster: AlertPoster,
    private val timeout: Duration = 20.seconds,
) {
    suspend fun once() {
        val rows = try {
            withTimeoutOrNull(timeout) { session.withClient { it.listSessions() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return

        // Another hub's seen sets would diff this hub's runs and missions
        // against the wrong ids, and its alerts name other sessions (review w16-1).
        session.credentials()?.hub?.let { hub -> if (prefs.keepSeenForHub(hub)) poster.withdrawAll() }
        // The fleet's matrix and quiet hours as the hub has them now, not as
        // the app last read them while it was open (reviews w11-4, w13-1). A
        // hub or token without `get_settings`, or a failed read, keeps the
        // last ones.
        try {
            withTimeoutOrNull(timeout) { session.withClient { it.settingValues() } }?.let(prefs::writeFleetNotify)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        sessions(rows)
        routines()
        missions()
    }

    private suspend fun sessions(rows: List<SessionRow>) {
        val before = prefs.readSeen()
        // What is shared with this person, and at what level: a session they
        // only watch is not theirs to answer (review w08-3). A hub without
        // sharing (the read is refused) treats every row as theirs, as
        // before. A read that timed out skips this look instead: with
        // nothing known every row reads as theirs, and a watched one would
        // ring and be taken down at the next look (reviews w11-5, w05-1).
        val access = try {
            withTimeoutOrNull(timeout) { session.withClient { MyAccess.of(it.myGrants()) } } ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            MyAccess.UNKNOWN
        }
        val (alerts, after) = needsYouAlerts(before.orEmpty(), rows, access)
        if (before != null) {
            // The kinds This phone turned off, and those the fleet's matrix or
            // quiet hours hold back, are still remembered as seen, so turning
            // one back on (or quiet hours ending) does not replay them.
            // Read once, so an alert is posted, held or dropped by one clock.
            val minute = localMinuteOfDay()
            for (alert in alerts) {
                when {
                    prefs.notifyAllows(alert.reason, minute) -> poster.post(alert)
                    prefs.heldByQuietHours(alert.reason, minute) -> prefs.holdForQuiet(alert.sessionId)
                }
            }
            before.filter { (id, reason) -> reason != null && after[id] == null }.keys.forEach(poster::withdraw)
            // Quiet hours over: one word for what they held that still waits (review w11-12).
            prefs.quietSummaryDue(after, minute)?.let(poster::postQuietSummary)
        }
        prefs.writeSeen(after)
    }

    /**
     * The matrix's Routine failed row (review r19, R19-5): the session list
     * carries no routine runs, so one more look, at `routines { failing }`.
     * A hub or token without it answers an error, and the check says nothing.
     */
    private suspend fun routines() {
        val failing = try {
            withTimeoutOrNull(timeout) { session.withClient { it.failingRoutines() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val (alerts, after) = routineFailedAlerts(prefs.readRoutineSeen(), failing)
        if (prefs.notifyAllows(ROUTINE_FAILED_REASON)) alerts.forEach(poster::postRoutine)
        prefs.writeRoutineSeen(after)
    }

    /**
     * A mission that waits on a person (contract 15's `waiting_on`, gap plan
     * G5.7): the session list carries no missions, so one more look, at
     * `work_missions`. A hub or token without it answers an error, and the
     * check says nothing.
     */
    private suspend fun missions() {
        val missions = try {
            withTimeoutOrNull(timeout) { session.withClient { it.workMissions(waiting = true) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val before = prefs.readMissionSeen()
        val (alerts, after) = missionWaitAlerts(before, missions)
        if (prefs.notifyAllows(MISSION_WAITING_REASON)) alerts.forEach(poster::postMission)
        missionWaitsResolved(before, after).forEach(poster::withdrawMission)
        prefs.writeMissionSeen(after)
    }
}

/**
 * While the app is open on a platform with no always-on watcher, keep the
 * seen set current — so the next background look compares against what the
 * person last saw, not against what the last background look saw — and take
 * down the notification of any session that stops needing them meanwhile.
 *
 * Posts nothing: on screen, the list already says it. Runs until cancelled.
 */
suspend fun keepSeenWhileOpen(fleet: FleetState, prefs: Prefs, poster: AlertPoster) {
    needsYouEvents(fleet, prefs.readSeen()) { prefs.writeSeen(it) }
        .collect { event -> if (event is NeedsYouResolved) poster.withdraw(event.sessionId) }
}
