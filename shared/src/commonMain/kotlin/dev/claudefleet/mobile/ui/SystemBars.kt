package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/**
 * While [hidden], the phone's own status and navigation bars are out of the
 * way and a swipe from the edge brings them back for a moment (redesign
 * 14.21, MobileFullscreen). Back to normal when [hidden] turns false or the
 * caller leaves the composition.
 */
@Composable
internal expect fun HideSystemBars(hidden: Boolean)
