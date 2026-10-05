package dev.claudefleet.mobile.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundRefreshStatus
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNUserNotificationCenter

/**
 * [BackgroundNotifier] on iOS: the person's choice in `NSUserDefaults`, a
 * `BGAppRefreshTask` requested while it is on, and an honest [note], because
 * iOS — not the app — decides when the check runs.
 */
class IosBackgroundNotifier(
    val alertPoster: IosAlertPoster,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : BackgroundNotifier {
    private val _enabled = MutableStateFlow(defaults.boolForKey(KEY))
    private val _note = MutableStateFlow<String?>(backgroundNote(notificationsAllowed = null, refreshAvailable = true))

    override val supported: Boolean = true
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val note: StateFlow<String?> = _note.asStateFlow()
    override val poster: AlertPoster? get() = alertPoster

    override fun setEnabled(on: Boolean) {
        defaults.setBool(on, forKey = KEY)
        _enabled.value = on
        if (on) {
            // On a simulator this always fails with "unavailable"; the choice
            // still stands, and a device schedules it.
            submitNeedsYouRefresh()?.let { println("needs-you: refresh not scheduled: $it") }
        } else {
            cancelNeedsYouRefresh()
            alertPoster.withdrawAll()
        }
    }

    /** Main thread only: `backgroundRefreshStatus` is UIKit. Settings calls it on resume. */
    override fun refreshNote() {
        val refresh = UIApplication.sharedApplication.backgroundRefreshStatus ==
            UIBackgroundRefreshStatus.UIBackgroundRefreshStatusAvailable
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val allowed = settings?.let { it.authorizationStatus != UNAuthorizationStatusDenied }
            _note.value = backgroundNote(allowed, refresh)
        }
    }

    private companion object {
        const val KEY = "needs_you"
    }
}
