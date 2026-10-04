package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session screen's chrome policy: when the header and the footer fold to
 * one line so the conversation gets the height, and the cases that keep them
 * open whatever else is true.
 */
class SessionChromeTest {

    private val reading = ChromeInputs(atBottom = false, readingUp = true)

    @Test
    fun at_the_newest_turn_everything_is_full() {
        assertEquals(Chrome.Full, headerChrome(ChromeInputs()))
        assertEquals(Chrome.Full, footerChrome(ChromeInputs()))
    }

    @Test
    fun reading_back_folds_both() {
        assertEquals(Chrome.Compact, headerChrome(reading))
        assertEquals(Chrome.Compact, footerChrome(reading))
    }

    @Test
    fun scrolling_towards_the_newest_without_reaching_it_unfolds_both() {
        val down = reading.copy(readingUp = false)
        assertEquals(Chrome.Full, headerChrome(down))
        assertEquals(Chrome.Full, footerChrome(down))
    }

    @Test
    fun a_stale_reading_up_at_the_newest_turn_does_not_fold() {
        val atNewest = ChromeInputs(atBottom = true, readingUp = true)
        assertEquals(Chrome.Full, headerChrome(atNewest))
        assertEquals(Chrome.Full, footerChrome(atNewest))
    }

    @Test
    fun immersive_folds_both_even_at_the_newest_turn() {
        val immersive = ChromeInputs(immersive = true)
        assertEquals(Chrome.Compact, headerChrome(immersive))
        assertEquals(Chrome.Compact, footerChrome(immersive))
    }

    @Test
    fun typing_folds_the_header_and_keeps_the_footer() {
        val typing = ChromeInputs(composing = true)
        assertEquals(Chrome.Compact, headerChrome(typing))
        assertEquals(Chrome.Full, footerChrome(typing))
    }

    @Test
    fun a_waiting_agent_keeps_the_footer_open_while_reading_and_immersive() {
        assertEquals(Chrome.Full, footerChrome(reading.copy(needsAnswer = true)))
        assertEquals(Chrome.Full, footerChrome(ChromeInputs(immersive = true, needsAnswer = true)))
    }

    @Test
    fun a_half_written_draft_keeps_the_footer_open() {
        assertEquals(Chrome.Full, footerChrome(reading.copy(hasDraft = true)))
        assertEquals(Chrome.Full, footerChrome(ChromeInputs(immersive = true, hasDraft = true)))
    }

    @Test
    fun the_direction_flips_only_past_the_threshold() {
        val direction = ReadingDirection(thresholdPx = 10f)
        assertFalse(direction.onDrag(9f))
        assertTrue(direction.onDrag(1f))
    }

    @Test
    fun a_wobble_against_the_run_does_not_flip_it_back() {
        val direction = ReadingDirection(thresholdPx = 10f)
        direction.onDrag(12f)
        assertTrue(direction.onDrag(-9f))
        assertTrue(direction.onDrag(5f))
        // A fresh run the other way, from zero, past the threshold.
        assertTrue(direction.onDrag(-9f))
        assertFalse(direction.onDrag(-1f))
    }

    @Test
    fun a_zero_delta_changes_nothing() {
        val direction = ReadingDirection(thresholdPx = 10f)
        direction.onDrag(10f)
        assertTrue(direction.onDrag(0f))
    }

    @Test
    fun reset_clears_the_direction_and_the_run() {
        val direction = ReadingDirection(thresholdPx = 10f)
        direction.onDrag(10f)
        direction.reset()
        assertFalse(direction.readingUp)
        // The run started again from zero, not from the 10 before the reset.
        assertFalse(direction.onDrag(9f))
    }

    @Test
    fun a_phone_on_its_side_starts_folded_and_a_phone_upright_does_not() {
        assertTrue(startsImmersive(windowHeightDp = 411f))
        assertFalse(startsImmersive(windowHeightDp = 480f))
        assertFalse(startsImmersive(windowHeightDp = 915f))
    }

    @Test
    fun a_hint_is_shown_once() {
        val hints = Hints(FakePrefs())
        assertFalse(hints.shown(Hints.DOUBLE_TAP))
        hints.markShown(Hints.DOUBLE_TAP)
        hints.markShown(Hints.DOUBLE_TAP)
        assertTrue(hints.shown(Hints.DOUBLE_TAP))
        assertFalse(hints.shown("another"))
    }
}
