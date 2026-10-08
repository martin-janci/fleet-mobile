package dev.claudefleet.mobile.ui.kit

import dev.claudefleet.mobile.ui.theme.OrbitTokens
import dev.claudefleet.mobile.ui.theme.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitTest {

    @Test
    fun the_six_status_words_are_the_manuals() {
        assertEquals(
            listOf("Needs you", "Working", "Failed", "Done", "Paused", "Idle"),
            StatusWord.entries.map { it.label },
        )
    }

    @Test
    fun every_tone_has_its_word() {
        assertEquals(StatusWord.NEEDS_YOU, StatusWord.of(StatusTone.BLOCKED))
        assertEquals(StatusWord.WORKING, StatusWord.of(StatusTone.WORKING))
        assertEquals(StatusWord.FAILED, StatusWord.of(StatusTone.STUCK))
        assertEquals(StatusWord.FAILED, StatusWord.of(StatusTone.FAILED))
        assertEquals(StatusWord.DONE, StatusWord.of(StatusTone.COMPLETED))
        assertEquals(StatusWord.IDLE, StatusWord.of(StatusTone.IDLE))
        assertEquals(StatusWord.IDLE, StatusWord.of(StatusTone.STOPPED))
        assertNull(StatusWord.of(StatusTone.UNKNOWN), "an unknown state has no word; the row shows a dash")
    }

    @Test
    fun only_needs_you_is_amber_and_only_failed_is_red() {
        for (dark in listOf(true, false)) {
            val o = dev.claudefleet.mobile.ui.theme.OrbitColors(isDark = dark)
            val amber = StatusWord.entries.filter { it.color(o) == o.statusWaiting }
            val red = StatusWord.entries.filter { it.color(o) == o.statusFailed }
            assertEquals(listOf(StatusWord.NEEDS_YOU), amber)
            assertEquals(listOf(StatusWord.FAILED), red)
        }
    }

    @Test
    fun the_bar_has_the_manuals_destinations_in_order() {
        assertEquals(listOf("Inbox", "Sessions", "Control", "Work", "More"), OrbitDestinations.map { it.label })
        assertEquals(OrbitDestinations.size, OrbitDestinations.map { it.key }.toSet().size, "keys are unique")
    }

    @Test
    fun the_badge_counts_up_to_99() {
        assertNull(badgeText(0))
        assertNull(badgeText(-1))
        assertEquals("1", badgeText(1))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }

    @Test
    fun the_bar_is_tall_enough_for_a_thumb() {
        assertTrue(OrbitTokens.spacing("tab-bar-h") >= OrbitTokens.spacing("touch-min"))
        assertTrue(OrbitTokens.spacing("phone-row-min") >= OrbitTokens.spacing("touch-min"))
    }
}
