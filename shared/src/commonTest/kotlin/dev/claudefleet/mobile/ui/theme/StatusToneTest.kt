package dev.claudefleet.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusToneTest {
    @Test
    fun stuck_outranks_every_status() {
        assertEquals(StatusTone.STUCK, StatusTone.of("working", "press_enter"))
        assertEquals(StatusTone.STUCK, StatusTone.of(null, "oom"))
    }

    @Test
    fun each_status_has_its_tone_and_unknown_is_quiet() {
        assertEquals(StatusTone.WORKING, StatusTone.of("working", null))
        assertEquals(StatusTone.IDLE, StatusTone.of("idle", null))
        assertEquals(StatusTone.BLOCKED, StatusTone.of("blocked", null))
        assertEquals(StatusTone.FAILED, StatusTone.of("failed", null))
        assertEquals(StatusTone.COMPLETED, StatusTone.of("completed", null))
        assertEquals(StatusTone.STOPPED, StatusTone.of("stopped", null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of(null, null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of("", null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of("hibernating", null), "a status this build does not know is drawn quietly, not as an error")
    }

    @Test
    fun labels_read_as_words_and_unknown_is_a_dash() {
        // One vocabulary: stuck is "stuck" (the strip says on what), blocked is "waiting".
        assertEquals("stuck", statusLabel("working", "press_enter"))
        assertEquals("waiting", statusLabel("blocked", null))
        assertEquals("done", statusLabel("completed", null))
        assertEquals("working", statusLabel("working", null))
        assertEquals("—", statusLabel(null, null))
        assertEquals("hibernating", statusLabel("hibernating", null), "an unknown word is still shown so a person can read it")
    }
}
