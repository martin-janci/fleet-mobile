package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackgroundNoteTest {

    @Test
    fun normally_it_says_ios_decides() {
        assertEquals(
            "iOS decides when to check — often within the hour, sometimes not at all. Keep the app open for prompt alerts.",
            backgroundNote(notificationsAllowed = true, refreshAvailable = true),
        )
        assertEquals(backgroundNote(true, true), backgroundNote(null, true), "not asked yet reads as the normal case")
    }

    @Test
    fun denied_notifications_win_over_everything() {
        assertEquals("Notifications are off for Fleet in iOS Settings.", backgroundNote(false, false))
    }

    @Test
    fun background_refresh_off_is_said() {
        assertEquals(
            "Background App Refresh is off, so iOS will not check while the app is closed.",
            backgroundNote(true, refreshAvailable = false),
        )
    }

    @Test
    fun a_platform_that_says_nothing_keeps_the_default_line() {
        assertNull(NoBackgroundNotifier.note.value)
        assertNull(NoBackgroundNotifier.poster)
    }

    /** Review w16-3: on Android a blocked app or a silenced alerts channel is said under the switch. */
    @Test
    fun android_says_when_the_system_keeps_the_alerts_from_you() {
        assertNull(androidNotificationsNote(appNotificationsOn = true, alertsChannelOn = true))
        assertEquals(
            "Notifications are off for Orbit Fleet in Android settings, so nothing reaches you.",
            androidNotificationsNote(appNotificationsOn = false, alertsChannelOn = true),
        )
        assertEquals(
            "The \"A session needs you\" notifications are off in Android settings, so nothing reaches you.",
            androidNotificationsNote(appNotificationsOn = true, alertsChannelOn = false),
        )
    }

    /** Review w16-2: the watcher's ongoing notice stops claiming to watch when it cannot. */
    @Test
    fun the_watch_notice_says_when_it_is_not_watching() {
        val utc: (Long) -> Int = { 0 }
        val down = 18 * 3600L + 40 * 60
        assertNull(watchingNotice(ConnectionStatus.Connected("1.0"), null, utc))
        assertNull(watchingNotice(ConnectionStatus.Reconnecting(1, null), down, utc), "a blip is not news")
        assertNull(watchingNotice(ConnectionStatus.Offline("not connected yet"), down, utc))
        assertEquals(
            "Can't reach the hub since 18:40. No alerts until it answers.",
            watchingNotice(ConnectionStatus.Reconnecting(WATCH_GRACE_ATTEMPTS, "Can't reach the hub from this network."), down, utc),
        )
        assertEquals(
            "Not watching: this hub needs a newer version of the app. Update it to get alerts again.",
            watchingNotice(ConnectionStatus.Refused("this hub is newer than the app"), down, utc),
        )
    }
}
