package dev.claudefleet.mobile

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

internal actual fun platformName(): String = "iOS"

internal actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()

internal actual fun utcOffsetSeconds(atEpochSeconds: Long): Int =
    NSTimeZone.localTimeZone.secondsFromGMTForDate(NSDate.dateWithTimeIntervalSince1970(atEpochSeconds.toDouble())).toInt()
