package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * On iOS the status bar belongs to the hosting view controller
 * (`prefersStatusBarHidden`), which Compose cannot reach. The SwiftUI side
 * listens to [iosSystemBars] (`ContentView.swift`, through
 * `MainViewControllerKt.onSystemBarsHidden`) and hides the status bar and
 * the home indicator while a full-screen layout holds it.
 */
@Composable
internal actual fun HideSystemBars(hidden: Boolean) {
    DisposableEffect(hidden) {
        if (hidden) iosSystemBars.hold()
        onDispose { if (hidden) iosSystemBars.release() }
    }
}

/** The one hold for the process; touched only from the main thread, where Compose and SwiftUI both run. */
internal val iosSystemBars = SystemBarsHold()

/**
 * On iOS the status bar's style belongs to the hosting view controller, as its
 * visibility does ([HideSystemBars]); the SwiftUI host follows the system
 * appearance. Nothing for Compose to set here.
 */
@Composable
internal actual fun SystemBarsAppearance(dark: Boolean) = Unit
