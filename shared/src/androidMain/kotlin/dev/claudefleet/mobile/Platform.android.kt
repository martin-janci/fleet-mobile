package dev.claudefleet.mobile

internal actual fun platformName(): String = "Android"

internal actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000
