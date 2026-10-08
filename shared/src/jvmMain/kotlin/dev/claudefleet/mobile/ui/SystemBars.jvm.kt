package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** A desktop test window has no system bars. */
@Composable
internal actual fun HideSystemBars(hidden: Boolean) = Unit
