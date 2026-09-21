package dev.claudefleet.mobile

internal actual fun platformName(): String = "iOS"

internal actual fun epochSeconds(): Long = platform.Foundation.NSDate().timeIntervalSince1970.toLong()
