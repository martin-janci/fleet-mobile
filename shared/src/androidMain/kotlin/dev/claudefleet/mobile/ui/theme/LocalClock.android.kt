package dev.claudefleet.mobile.ui.theme

import java.util.Calendar
import java.util.TimeZone

/** The device's wall clock and zone, through java.util. */
actual fun localClock(): LocalClock {
    val zone = TimeZone.getDefault()
    val now = Calendar.getInstance(zone)
    val hour = 3_600_000.0
    return LocalClock(
        dayOfYear = now.get(Calendar.DAY_OF_YEAR),
        hour = now.get(Calendar.HOUR_OF_DAY),
        minute = now.get(Calendar.MINUTE),
        utcOffsetHours = zone.getOffset(now.timeInMillis) / hour,
        standardOffsetHours = zone.rawOffset / hour,
    )
}
