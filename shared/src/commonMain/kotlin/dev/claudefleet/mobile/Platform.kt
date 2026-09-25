package dev.claudefleet.mobile

/**
 * The first `expect`/`actual` pair, present so that each source set is really
 * wired up. Secure storage and the camera scanner follow the same shape.
 */
internal expect fun platformName(): String

/** Unix seconds, so a UI clock can be read without pulling in a date library. */
internal expect fun epochSeconds(): Long

/**
 * How far this device's local time was ahead of UTC at [atEpochSeconds], in
 * seconds (negative west of Greenwich) — what turns "now" into "since local
 * midnight" for the Today sheet without a date library.
 */
internal expect fun utcOffsetSeconds(atEpochSeconds: Long): Int
