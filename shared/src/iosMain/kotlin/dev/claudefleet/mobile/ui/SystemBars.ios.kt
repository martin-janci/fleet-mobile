package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/**
 * Nothing yet: on iOS the status bar belongs to the hosting view controller
 * (`prefersStatusBarHidden`), which the app's Swift side would have to ask.
 * The full-screen layouts still take the whole Compose window.
 */
@Composable
internal actual fun HideSystemBars(hidden: Boolean) = Unit
