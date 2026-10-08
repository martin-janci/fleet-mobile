package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where "needs you" notifications go on a platform that posts them from shared code. */
interface AlertPoster {
    fun post(alert: NeedsYouAlert)

    fun withdraw(sessionId: Long)
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

        val before = prefs.readSeen()
        val (alerts, after) = needsYouAlerts(before.orEmpty(), rows)
        if (before != null) {
            // The kinds This phone turned off are still remembered as seen,
            // so turning one back on does not replay what was skipped.
            val kinds = prefs.notifyKinds()
            alerts.filter { kinds.allows(it.reason) }.forEach(poster::post)
            before.filter { (id, reason) -> reason != null && after[id] == null }.keys.forEach(poster::withdraw)
        }
        prefs.writeSeen(after)
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
