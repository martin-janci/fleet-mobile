package dev.claudefleet.mobile.ui.kit

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import dev.claudefleet.mobile.data.STOPPED
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

    /** Review r13 (P13-13): before the first connect and while backgrounded nothing has failed yet. */
    @Test
    fun not_started_or_stopped_reads_as_the_first_attempt_not_signal_lost() {
        for (reason in listOf(FleetRepository.NOT_STARTED, STOPPED)) {
            assertEquals(PhoneConnection.Reconnecting(1), phoneConnection(ConnectionStatus.Offline(reason), hubLost = false), reason)
            // Past hub-lost-after it may say so, but never with the internal words.
            assertEquals(PhoneConnection.Offline(null), phoneConnection(ConnectionStatus.Offline(reason), hubLost = true), reason)
        }
    }

    /** P13-4: the banner's line is the sentence; the technical half rides along for Details only. */
    @Test
    fun the_reconnect_details_ride_along_for_the_details_line() {
        val retrying = ConnectionStatus.Reconnecting(3, "Can't reach the hub from this network.", "could not reach the hub (IOException)")
        assertEquals(
            PhoneConnection.Offline("Can't reach the hub from this network.", "could not reach the hub (IOException)"),
            phoneConnection(retrying, hubLost = true),
        )
    }

    /** P13-9: a failed read is never drawn as the empty state. */
    @Test
    fun a_failed_read_is_not_an_empty_list() {
        assertEquals(ListBody.Failed, listBody(loaded = true, failed = true, empty = true))
        assertEquals(ListBody.Failed, listBody(loaded = false, failed = true, empty = true))
        assertEquals(ListBody.Empty, listBody(loaded = true, failed = false, empty = true))
        assertEquals(ListBody.Loading, listBody(loaded = false, failed = false, empty = true))
        // Rows already on screen stay; the banner says the refresh failed.
        assertEquals(ListBody.Rows, listBody(loaded = true, failed = true, empty = false))
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

    @Test
    fun a_failed_load_and_a_failed_show_more_share_one_title_shape() {
        assertEquals("Couldn't load your work", loadFailedTitle("your work"))
        assertEquals("Couldn't load more tasks", loadFailedTitle("more tasks"))
    }
}
