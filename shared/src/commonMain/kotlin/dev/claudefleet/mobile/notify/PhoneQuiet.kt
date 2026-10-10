package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.Prefs

/**
 * This phone's own quiet hours (MobileSettings · This phone: "Quiet hours
 * 22:00 – 07:00 · Needs you still comes through"). Kept on the device and
 * applied after the fleet's matrix and quiet hours, so a phone can be quieter
 * than the fleet without changing what the desktop and other phones get.
 * [range] is minutes of the day on this phone's clock (null: off); with
 * [needsYouThrough] a session waiting on a person still comes through.
 */
data class PhoneQuiet(val range: Pair<Int, Int>? = null, val needsYouThrough: Boolean = false) {
    fun allows(reason: String?, minute: Int): Boolean =
        !inQuietHours(range, minute) || (needsYouThrough && notifyKindOf(reason) == NotifyKind.NEEDS_YOU)

    /** "22:00-07:00", the hub's own form, or empty when off. */
    val text: String get() = range?.let { (from, until) -> "${clockText(from)}-${clockText(until)}" }.orEmpty()

    companion object {
        /** What turning quiet hours on starts from: the board's night. */
        val NIGHT: Pair<Int, Int> = 22 * 60 to 7 * 60
    }
}

/** Minute of the day 0–1439 as "07:05". */
fun clockText(minute: Int): String {
    val m = minute.mod(24 * 60)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/** "07:05" (or "7:05") as a minute of the day; null when it is not a time. Read as [parseQuietHours] reads each end. */
fun parseClock(v: String): Int? {
    val parts = v.trim().split(':')
    if (parts.size != 2) return null
    val (h, m) = parts
    if (h.isEmpty() || h.length > 2 || m.length != 2) return null
    val hh = h.toIntOrNull() ?: return null
    val mm = m.toIntOrNull() ?: return null
    return if (hh in 0..23 && mm in 0..59) hh * 60 + mm else null
}

private const val PHONE_QUIET_PREF = "phone.quiet"

/** Read fresh on every alert, like the notification kinds: the Android service has its own container. */
fun Prefs.phoneQuiet(): PhoneQuiet {
    val stored = getStringList(PHONE_QUIET_PREF)
    return PhoneQuiet(parseQuietHours(stored.getOrNull(0).orEmpty()), stored.getOrNull(1) == NEEDS_YOU_THROUGH)
}

fun Prefs.writePhoneQuiet(quiet: PhoneQuiet) {
    putStringList(PHONE_QUIET_PREF, listOf(quiet.text, if (quiet.needsYouThrough) NEEDS_YOU_THROUGH else ""))
}

private const val NEEDS_YOU_THROUGH = "needs_you"
