package dev.claudefleet.mobile

/**
 * The first `expect`/`actual` pair, present so that each source set is really
 * wired up. Secure storage and the camera scanner follow the same shape.
 */
internal expect fun platformName(): String

/** Unix seconds, so a UI clock can be read without pulling in a date library. */
internal expect fun epochSeconds(): Long
