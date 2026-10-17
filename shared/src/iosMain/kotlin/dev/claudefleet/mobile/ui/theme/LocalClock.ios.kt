package dev.claudefleet.mobile.ui.theme

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone

/** The device's wall clock and zone, through Foundation. */
actual fun localClock(): LocalClock {
    val now = NSDate()
    val calendar = NSCalendar.currentCalendar
    val parts = calendar.components(NSCalendarUnitHour or NSCalendarUnitMinute, fromDate = now)
    val day = calendar.ordinalityOfUnit(NSCalendarUnitDay, inUnit = NSCalendarUnitYear, forDate = now)
    val zone = NSTimeZone.localTimeZone
    val offset = zone.secondsFromGMTForDate(now) / 3600.0
    return LocalClock(
        dayOfYear = day.toInt(),
        hour = parts.hour.toInt(),
        minute = parts.minute.toInt(),
        utcOffsetHours = offset,
        standardOffsetHours = offset - zone.daylightSavingTimeOffsetForDate(now) / 3600.0,
    )
}
