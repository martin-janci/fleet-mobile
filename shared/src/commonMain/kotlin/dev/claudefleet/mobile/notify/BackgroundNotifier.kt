package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.ui.clockLabel
import dev.claudefleet.mobile.utcOffsetSeconds
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

    /**
     * Take down every "needs you" alert this app has up — when the pairing
     * goes, or another hub's replaces it: their ids name the old fleet's
     * sessions and missions (review w16-1).
     */
    fun withdrawAll() {}
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

/**
 * The Android line under the switch when the system keeps the alerts from
 * the person (review w16-3): notifications off for the app, or its "A session
 * needs you" channel silenced. Null when they get through.
 */
fun androidNotificationsNote(appNotificationsOn: Boolean, alertsChannelOn: Boolean): String? = when {
    !appNotificationsOn -> "Notifications are off for Orbit Fleet in Android settings, so nothing reaches you."
    !alertsChannelOn -> "The \"A session needs you\" notifications are off in Android settings, so nothing reaches you."
    else -> null
}

/** Reconnect attempts before the watcher's notice says it cannot reach the hub: a blip is not news. */
const val WATCH_GRACE_ATTEMPTS: Int = 3

/**
 * What Android's ongoing "Watching the fleet" notice says while the watch
 * cannot do its job (review w16-2): the hub refused this build, or it has
 * not answered for [WATCH_GRACE_ATTEMPTS] tries ([downSince], unix seconds,
 * when the stream stopped). Null while it watches, and for a short reconnect.
 */
fun watchingNotice(
    status: ConnectionStatus,
    downSince: Long?,
    offsetSeconds: (Long) -> Int = { utcOffsetSeconds(it) },
): String? {
    val since = downSince?.let { " since ${clockLabel(it, offsetSeconds(it))}" }.orEmpty()
    return when (status) {
        is ConnectionStatus.Connected -> null
        is ConnectionStatus.Refused -> "Not watching: this hub needs a newer version of the app. Update it to get alerts again."
        is ConnectionStatus.Reconnecting ->
            if (status.attempt < WATCH_GRACE_ATTEMPTS) null else "Can't reach the hub$since. No alerts until it answers."
        // Not started, stopped or revoked: the service says so by stopping, not here.
        is ConnectionStatus.Offline -> null
    }
}

/** No background notifications on this platform. */
object NoBackgroundNotifier : BackgroundNotifier {
    override val supported: Boolean = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override fun setEnabled(on: Boolean) = Unit
}
