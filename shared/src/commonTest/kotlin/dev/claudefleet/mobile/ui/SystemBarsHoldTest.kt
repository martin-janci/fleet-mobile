package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iOS status bar's hold (redesign 14.21): counted, and heard only when the answer changes. */
class SystemBarsHoldTest {

    @Test
    fun a_listener_hears_the_current_answer_and_each_change() {
        val hold = SystemBarsHold()
        val heard = mutableListOf<Boolean>()
        hold.listen { heard += it }
        hold.hold()
        hold.release()
        assertEquals(listOf(false, true, false), heard)
    }

    @Test
    fun the_bars_come_back_only_when_the_last_holder_lets_go() {
        val hold = SystemBarsHold()
        val heard = mutableListOf<Boolean>()
        hold.hold()
        hold.listen { heard += it }
        hold.hold()
        hold.release()
        assertTrue(hold.hidden, "one screen still holds it")
        hold.release()
        assertFalse(hold.hidden)
        assertEquals(listOf(true, false), heard)
    }

    @Test
    fun a_release_with_nothing_held_says_nothing() {
        val hold = SystemBarsHold()
        val heard = mutableListOf<Boolean>()
        hold.listen { heard += it }
        hold.release()
        hold.hold()
        assertEquals(listOf(false, true), heard)
    }
}
