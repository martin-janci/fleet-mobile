package dev.claudefleet.mobile.notify

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSDate

actual fun localMinuteOfDay(): Int {
    val parts = NSCalendar.currentCalendar.components(NSCalendarUnitHour or NSCalendarUnitMinute, fromDate = NSDate())
    return (parts.hour * 60 + parts.minute).toInt()
}
