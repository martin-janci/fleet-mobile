package dev.claudefleet.mobile.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Being told when a session needs you while the app is not on screen. On
 * Android a foreground service holds the hub's event stream open for it —
 * with its own ongoing notification and some battery, which is why it is the
 * person's choice and off until they turn it on. iOS cannot keep a connection
 * open in the background; it checks when the system wakes it (`NeedsYouCheck`)
 * and says so through [note].
 */
interface BackgroundNotifier {
    val supported: Boolean
    val enabled: StateFlow<Boolean>
    fun setEnabled(on: Boolean)

    /**
     * What to say under the switch instead of the default line, or null to keep
     * it. iOS uses it to be honest that the system, not the app, decides when a
     * check happens.
     */
    val note: StateFlow<String?> get() = NO_NOTE

    /**
     * Non-null on a platform with no always-on watcher. The open app then keeps
     * the seen set itself and withdraws through this (`keepSeenWhileOpen`).
     * Android's service keeps its own, so it leaves this null.
     */
    val poster: AlertPoster? get() = null

    /** Re-read whatever [note] depends on — called when Settings comes back on screen. */
    fun refreshNote() {}
}

private val NO_NOTE: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

/**
 * The iOS line under the switch. [notificationsAllowed] is null before the
 * person has been asked, which reads as the normal case.
 */
fun backgroundNote(notificationsAllowed: Boolean?, refreshAvailable: Boolean): String = when {
    notificationsAllowed == false -> "Notifications are off for Fleet in iOS Settings."
    !refreshAvailable -> "Background App Refresh is off, so iOS will not check while the app is closed."
    else -> "iOS decides when to check — often within the hour, sometimes not at all. Keep the app open for prompt alerts."
}

/** No background notifications on this platform. */
object NoBackgroundNotifier : BackgroundNotifier {
    override val supported: Boolean = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun setEnabled(on: Boolean) = Unit
}
