package dev.claudefleet.mobile.notify

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
}
