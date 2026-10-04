package dev.claudefleet.mobile.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Being told when a session needs you while the app is not on screen. On
 * Android a foreground service holds the hub's event stream open for it —
 * with its own ongoing notification and some battery, which is why it is the
 * person's choice and off until they turn it on. A platform that cannot do it
 * ([supported] false — iOS, without a push service in the hub) offers nothing.
 */
interface BackgroundNotifier {
    val supported: Boolean
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)
}

/** No background notifications on this platform. */
object NoBackgroundNotifier : BackgroundNotifier {
    override val supported: Boolean = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun setEnabled(on: Boolean) = Unit
}
