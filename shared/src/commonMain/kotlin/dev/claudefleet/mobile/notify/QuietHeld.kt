package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.Prefs

/*
 * What quiet hours held back, said once when they end (review w11-12). A held
 * alert is still marked seen, so it is never replayed one by one; instead the
 * sessions it named are kept here, and the first look after quiet hours posts
 * one summary — "3 sessions need you" — for those that still wait. Sessions
 * only: they are what the summary counts.
 */

/** Cleared with the seen sets when the hub changes ([keepSeenForHub]). */
internal const val QUIET_HELD_PREF = "notify.quiet_held"

/**
 * Whether an alert for [reason] is held back by quiet hours alone — the
 * fleet's or this phone's — and not by This phone's switch or the fleet's
 * matrix row, which say no at any hour.
 */
fun Prefs.heldByQuietHours(reason: String?, minute: Int = localMinuteOfDay()): Boolean {
    if (notifyAllows(reason, minute) || !notifyKinds().allows(reason)) return false
    val fleet = fleetNotify()
    return fleet == null || notifyStateOf(reason) in fleet.phone
}

fun Prefs.quietHeld(): Set<Long> = getStringList(QUIET_HELD_PREF).mapNotNull { it.toLongOrNull() }.toSet()

/** Remember that quiet hours held back the alert of [sessionId]. */
fun Prefs.holdForQuiet(sessionId: Long) {
    val held = quietHeld()
    if (sessionId !in held) putStringList(QUIET_HELD_PREF, (held + sessionId).map { it.toString() })
}

/**
 * The summary owed now, against [waiting] (session id → its attention reason,
 * null or absent: it no longer waits): how many held sessions still wait and
 * would be posted at this [minute]. Those are forgotten, as are the held ones
 * that stopped waiting or that a switch now holds back; the ones quiet hours
 * still hold are kept for later. Null: nothing to say.
 */
fun Prefs.quietSummaryDue(waiting: Map<Long, String?>, minute: Int = localMinuteOfDay()): Int? {
    val held = quietHeld()
    if (held.isEmpty()) return null
    val due = held.filter { id -> waiting[id] != null && notifyAllows(waiting[id], minute) }
    val keep = held.filter { id -> waiting[id] != null && heldByQuietHours(waiting[id], minute) }
    putStringList(QUIET_HELD_PREF, keep.map { it.toString() })
    return due.size.takeIf { it > 0 }
}

/** The summary's title: "1 session needs you", "3 sessions need you". */
fun quietSummaryTitle(count: Int): String = if (count == 1) "1 session needs you" else "$count sessions need you"

/** The summary's line under the title. */
const val QUIET_SUMMARY_TEXT: String = "Held back during quiet hours"
