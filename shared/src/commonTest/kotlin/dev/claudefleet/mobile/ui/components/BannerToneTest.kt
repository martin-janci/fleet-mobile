package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.ui.Friendly
import dev.claudefleet.mobile.ui.friendlyWork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a banner draws for a [Friendly].
 *
 * A message that is not an error used to be dropped by the banner itself, so
 * every view model that stored one — "already running", a handover already
 * on its way, a resume that lost a race and names the session it made — was
 * storing a sentence nobody would ever read.
 */
class BannerToneTest {

    @Test
    fun nothing_to_say_draws_nothing() {
        assertNull(bannerTone(null))
    }

    @Test
    fun a_failure_is_drawn_as_an_error() {
        assertEquals(BannerTone.Error, bannerTone(Friendly("Cannot reach the hub", "Check the network.", isError = true)))
    }

    @Test
    fun a_note_is_drawn_as_information_not_dropped() {
        assertEquals(BannerTone.Info, bannerTone(Friendly("Already running", "PAY-9 is live on pine", isError = false)))
    }

    /** The work graph's own informational refusal is one of these. */
    @Test
    fun an_existing_work_refusal_reaches_the_screen_as_a_note() {
        val f = friendlyWork(HubError.Tool("E_EXISTS", "PAY-9 is live in w on pine"))

        assertEquals(BannerTone.Info, bannerTone(f))
        assertEquals("PAY-9 is live in w on pine", f.body)
    }
}
