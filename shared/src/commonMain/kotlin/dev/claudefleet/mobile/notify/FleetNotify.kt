package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.Prefs

/*
 * The hub's notifications matrix and quiet hours (claude-fleet 11.9) on the
 * phone: the fleet's Phone column says which session states reach a phone,
 * and quiet hours hold back every state but the ones let through. The hub
 * keeps them so the desktop and the phone follow the same answer; the phone
 * remembers the last values it read, so its background watcher can apply
 * them without a hub call. This phone's own switches still apply on top.
 */

const val NOTIFY_PHONE: String = "notify.phone"
const val NOTIFY_QUIET_HOURS: String = "notify.quiet_hours"
const val NOTIFY_QUIET_EXCEPT: String = "notify.quiet_except"

/** The minute of the day on this phone's own clock, 0–1439: quiet hours are each device's local time. */
expect fun localMinuteOfDay(): Int

/**
 * The matrix's row for an attention reason, as the desktop files them: a
 * failure is Failed, a finished task Done, a session stuck on something no
 * answer fixes (host down, account limit, credentials, a full context) is
 * Blocked, and the rest wait on a person.
 */
fun notifyStateOf(reason: String?): String = when (reason) {
    "failed", "stop_failed" -> "failed"
    DONE_REASON -> "done"
    "stuck", "host_down", "account_limit", "no_credentials", "context_full", "stale_working", "lifecycle", "ci_failing" -> "blocked"
    else -> "needs_you"
}

/** `22:00-07:30` → minutes of the day `(1320, 450)`; empty, malformed or equal ends → null. The hub's `parse_time_range`. */
fun parseQuietHours(v: String): Pair<Int, Int>? {
    val t = v.trim()
    if (t.isEmpty()) return null
    fun minute(s: String): Int? {
        val parts = s.trim().split(':')
        if (parts.size != 2) return null
        val (h, m) = parts
        if (h.isEmpty() || h.length > 2 || m.length != 2) return null
        val hh = h.toIntOrNull() ?: return null
        val mm = m.toIntOrNull() ?: return null
        return if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
    }
    val dash = t.indexOf('-')
    if (dash < 0) return null
    val from = minute(t.substring(0, dash)) ?: return null
    val until = minute(t.substring(dash + 1)) ?: return null
    return if (from == until) null else from to until
}

/** Whether [minute] falls in [range], which may run past midnight; its end is not inside. */
fun inQuietHours(range: Pair<Int, Int>?, minute: Int): Boolean {
    val (from, until) = range ?: return false
    return if (from < until) minute in from until until else minute >= from || minute < until
}

private fun states(v: String?): Set<String> = v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** What the fleet lets reach this phone, as last read from the hub. */
data class FleetNotify(
    val phone: Set<String>,
    val quiet: Pair<Int, Int>? = null,
    val quietExcept: Set<String> = emptySet(),
) {
    fun allows(reason: String?, minute: Int): Boolean {
        val state = notifyStateOf(reason)
        if (state !in phone) return false
        return !inQuietHours(quiet, minute) || state in quietExcept
    }

    companion object {
        /** From the hub's effective values; null when the hub has no matrix (before 11.9). */
        fun of(values: Map<String, String>): FleetNotify? {
            val phone = values[NOTIFY_PHONE] ?: return null
            return FleetNotify(states(phone), parseQuietHours(values[NOTIFY_QUIET_HOURS].orEmpty()), states(values[NOTIFY_QUIET_EXCEPT]))
        }
    }
}

private const val FLEET_NOTIFY_PREF = "fleet.notify"

/** The last matrix read from the hub, or null: then only This phone's switches decide. */
fun Prefs.fleetNotify(): FleetNotify? {
    val lines = getStringList(FLEET_NOTIFY_PREF)
    if (lines.isEmpty()) return null
    return FleetNotify.of(lines.mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap())
}

/** Remember the hub's notify values; a hub without them clears what an older read left. */
fun Prefs.writeFleetNotify(values: Map<String, String>) {
    if (values[NOTIFY_PHONE] == null) {
        putStringList(FLEET_NOTIFY_PREF, emptyList())
        return
    }
    putStringList(FLEET_NOTIFY_PREF, listOf(NOTIFY_PHONE, NOTIFY_QUIET_HOURS, NOTIFY_QUIET_EXCEPT).map { k -> "$k=${values[k].orEmpty()}" })
}

/** Whether an alert for [reason] is posted now: This phone's switch, then the fleet's matrix and quiet hours. */
fun Prefs.notifyAllows(reason: String?, minute: Int = localMinuteOfDay()): Boolean =
    notifyKinds().allows(reason) && (fleetNotify()?.allows(reason, minute) ?: true)
