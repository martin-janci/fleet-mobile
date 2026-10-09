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

/**
 * Who wants the bars hidden, for a platform whose bars belong to its host
 * rather than to Compose (iOS: the SwiftUI scene's `statusBar(hidden:)`).
 * Counted, so two screens that ask at once do not bring the bars back when
 * the first lets go; [listen] hears each change of the answer, and the
 * current one at once.
 */
internal class SystemBarsHold {
    private var holders = 0
    private var listener: ((Boolean) -> Unit)? = null

    val hidden: Boolean get() = holders > 0

    fun listen(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
        listener?.invoke(hidden)
    }

    fun hold() {
        holders++
        if (holders == 1) listener?.invoke(true)
    }

    fun release() {
        if (holders == 0) return
        holders--
        if (holders == 0) listener?.invoke(false)
    }
}

/**
 * The phone's own bar icons follow the app's theme, not the system's: dark
 * icons on the Light theme, light icons on the Dark one. On Android
 * `enableEdgeToEdge()` picks them once, from the system night mode at
 * `onCreate`, so a person who set the app to Dark on a Light phone got dark
 * clock and battery on a dark background. Re-applied whenever [dark] changes.
 */
@Composable
internal expect fun SystemBarsAppearance(dark: Boolean)
