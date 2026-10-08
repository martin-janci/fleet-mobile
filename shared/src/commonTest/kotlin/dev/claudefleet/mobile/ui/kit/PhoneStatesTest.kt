package dev.claudefleet.mobile.ui.kit

import dev.claudefleet.mobile.data.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules behind the phone's states and loaders (redesign step 14.12), as
 * values: when a loader may show, which state a connection draws, and what
 * the states say. `PhoneStatesUiTest` draws them.
 */
class PhoneStatesTest {

    @Test
    fun a_wait_under_400_ms_shows_no_loader() {
        assertEquals(400L, OrbitMotion.loaderDelayMs)
        assertFalse(loaderDue(0))
        assertFalse(loaderDue(399))
        assertTrue(loaderDue(400))
        assertTrue(loaderDue(5_000))
    }

    @Test
    fun the_motion_numbers_come_from_the_tokens() {
        assertEquals(2_400L, OrbitMotion.reducedFadeMs)
        assertEquals(6_000L, OrbitMotion.hubLostAfterMs)
    }

    @Test
    fun a_connected_hub_draws_nothing() {
        assertEquals(PhoneConnection.Live, phoneConnection(ConnectionStatus.Connected("0.5.4"), hubLost = false))
        // A stale "lost" flag never outlives the connection.
        assertEquals(PhoneConnection.Live, phoneConnection(ConnectionStatus.Connected(null), hubLost = true))
    }

    @Test
    fun a_reconnect_shows_the_gravity_well_until_the_hub_has_been_gone_6_s() {
        val retrying = ConnectionStatus.Reconnecting(attempt = 3, reason = "the network dropped")
        assertEquals(PhoneConnection.Reconnecting(3), phoneConnection(retrying, hubLost = false))
        assertEquals(PhoneConnection.Offline("the network dropped"), phoneConnection(retrying, hubLost = true))
    }

    @Test
    fun an_offline_hub_draws_signal_lost_with_its_reason() {
        assertEquals(
            PhoneConnection.Offline("the hub refused the token"),
            phoneConnection(ConnectionStatus.Offline("the hub refused the token"), hubLost = false),
        )
    }

    @Test
    fun a_refused_hub_is_words_only_never_offline() {
        val refused = phoneConnection(ConnectionStatus.Refused("this hub is newer than the app"), hubLost = true)
        assertEquals(PhoneConnection.Refused("this hub is newer than the app"), refused)
    }

    @Test
    fun the_offline_banner_says_how_old_the_rows_are() {
        assertEquals("Showing what it said at 14:52. Answers wait until it is back.", offlineDetail("14:52"))
        assertEquals("Showing what it last said. Answers wait until it is back.", offlineDetail(null))
    }

    @Test
    fun pull_to_refresh_says_what_letting_go_will_do() {
        assertEquals("Pull to refresh", pullLabel(0.4f, refreshing = false))
        assertEquals("Release to refresh", pullLabel(1f, refreshing = false))
        assertEquals("Refreshing", pullLabel(0f, refreshing = true))
    }

    @Test
    fun every_full_screen_wait_has_a_way_out() {
        assertEquals(
            listOf("Skip, open Inbox", "Back to the session", "Cancel", "Continue in the background"),
            FullscreenWait.entries.map { it.exitLabel },
        )
    }

    @Test
    fun the_galaxy_counts_real_progress() {
        val p = LoaderProgress(done = 7, total = 14, noun = "imported")
        assertEquals("7 of 14 imported", p.label)
        assertEquals(0.5f, p.fraction)
        assertEquals(0f, LoaderProgress(0, 0, "imported").fraction)
        assertEquals(1f, LoaderProgress(20, 14, "imported").fraction)
    }

    @Test
    fun radar_blips_land_on_the_dish() {
        for (i in 0 until 24) {
            val (x, y) = radarSpot(i)
            val dx = x - 0.5f
            val dy = y - 0.5f
            assertTrue(dx * dx + dy * dy < 0.5f * 0.5f, "blip $i at ($x, $y) is off the dish")
        }
    }
}
