package dev.claudefleet.mobile

/**
 * The first `expect`/`actual` pair, present so that each source set is really
 * wired up. Secure storage and the camera scanner follow the same shape.
 */
internal expect fun platformName(): String
