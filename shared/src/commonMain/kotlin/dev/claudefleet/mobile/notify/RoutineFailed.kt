package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.model.FailingRoutine
import dev.claudefleet.mobile.store.Prefs

/**
 * The matrix's Routine failed row (claude-fleet 11.9) as the notifier's
 * reason: not a session's attention reason, so it never reaches the Inbox's
 * session list; the Inbox lists the routine under its own Failed rows
 * (review w09-7). This phone's Failed switch covers it.
 */
const val ROUTINE_FAILED_REASON: String = "routine_failed"

/** One "a routine run failed" notification: which run, and its two lines. */
data class RoutineFailedAlert(val runId: Long, val routineId: Long, val title: String, val text: String)

/**
 * What a look at `routines { failing }` tells the notifier: each failed run
 * not in [seen] (run ids at the last look). With no last look, this one is
 * the baseline and nothing is news: a failure older than the watcher is on
 * the Inbox already (its Failed routine rows, review w09-7), as the desktop
 * has it (Attention.svelte, `openedAt`). A tap opens [RoutineFailedAlert.routineId]
 * in Automation (review w16-4).
 * The answer carries the run ids as of [failing], for the next look; a run
 * no longer failing (a Retry, a Pause) drops out of it.
 */
fun routineFailedAlerts(seen: Set<Long>?, failing: List<FailingRoutine>): Pair<List<RoutineFailedAlert>, Set<Long>> {
    val now = failing.map { it.run.id }.toSet()
    if (seen == null) return emptyList<RoutineFailedAlert>() to now
    // A routine this person may not change has no row on their Inbox and no
    // button for them: remembered, never announced (reviews w11-7, w05-8).
    val alerts = failing.filter { it.run.id !in seen && it.mayChange }.map { f ->
        RoutineFailedAlert(
            f.run.id,
            f.routine.id,
            "Routine ${f.routine.name.ifBlank { "#${f.routine.id}" }} failed",
            f.run.reason?.trim()?.takeIf { it.isNotEmpty() } ?: f.routine.hostAlias,
        )
    }
    return alerts to now
}

/** The failed runs the background check last saw, in [Prefs]; null before the first look. */
internal const val ROUTINE_FAILED_SEEN: String = "routine_failed_seen"
private const val ROUTINE_SEEN_HEADER = "v1"

fun Prefs.readRoutineSeen(): Set<Long>? =
    getStringList(ROUTINE_FAILED_SEEN).takeIf { ROUTINE_SEEN_HEADER in it }?.mapNotNull { it.toLongOrNull() }?.toSet()

fun Prefs.writeRoutineSeen(seen: Set<Long>) {
    putStringList(ROUTINE_FAILED_SEEN, listOf(ROUTINE_SEEN_HEADER) + seen.map { it.toString() })
}

/**
 * The always-on watcher's look at `routines { failing }` (Android's
 * `NeedsYouService`), remembering in [prefs] as iOS's background check does:
 * a run that failed while the watcher was down (the process killed and
 * restarted, an update, a reboot) is news when it comes back, not its
 * baseline. Before the first look ever there is nothing stored, so that look
 * stays the baseline. Written only when the set moves, not every poll.
 */
class RoutineFailedWatch(private val prefs: Prefs) {
    private var seen: Set<Long>? = prefs.readRoutineSeen()

    /** The alerts one look at [failing] makes. */
    fun look(failing: List<FailingRoutine>): List<RoutineFailedAlert> {
        val (alerts, now) = routineFailedAlerts(seen, failing)
        if (now != seen) prefs.writeRoutineSeen(now)
        seen = now
        return alerts
    }
}
