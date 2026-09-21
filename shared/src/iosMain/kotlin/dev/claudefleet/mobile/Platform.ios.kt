package dev.claudefleet.mobile

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

internal actual fun platformName(): String = "iOS"

internal actual fun epochSeconds(): Long = NSDate().timeIntervalSince1970.toLong()
