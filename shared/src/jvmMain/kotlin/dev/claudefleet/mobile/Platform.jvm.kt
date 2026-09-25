package dev.claudefleet.mobile

internal actual fun platformName(): String = "JVM"

internal actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000

internal actual fun utcOffsetSeconds(atEpochSeconds: Long): Int =
    java.util.TimeZone.getDefault().getOffset(atEpochSeconds * 1000) / 1000
