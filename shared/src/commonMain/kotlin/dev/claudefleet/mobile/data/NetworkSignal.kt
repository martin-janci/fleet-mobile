package dev.claudefleet.mobile.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The platform saying the network is back (Android's default-network
 * callback, while the app is in front). The stream's backoff reaches 30 s
 * after a minute without signal — a lift, the underground — so without this
 * the banner kept saying "reconnecting" for up to half a minute after the
 * phone had a network again. A platform that has no such hook (iOS today)
 * never emits, and the backoff stands as before.
 */
object NetworkSignal {
    private val back = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once each time a network becomes available. */
    val available: SharedFlow<Unit> = back.asSharedFlow()

    /** Called by the platform; conflated, so a burst of callbacks is one wake. */
    fun networkAvailable() {
        back.tryEmit(Unit)
    }
}
