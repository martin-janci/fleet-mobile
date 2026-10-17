package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Later on Control's escalation cards is kept on the phone (Control review F18/F19). */
class EscalationLaterTest {

    @Test
    fun a_card_put_off_stays_off_after_a_restart() {
        val prefs = FakePrefs()
        EscalationLater(prefs).putOff("2:waiting:4")
        val again = EscalationLater(prefs)
        assertEquals(listOf("2:waiting:4"), again.later.value)
        again.putOff("host:htz")
        assertEquals(listOf("2:waiting:4", "host:htz"), EscalationLater(prefs).later.value)
    }

    @Test
    fun without_prefs_it_keeps_them_for_this_run() {
        val later = EscalationLater()
        later.putOff("a")
        assertEquals(listOf("a"), later.later.value)
    }

    @Test
    fun past_three_cards_the_rest_are_counted() {
        assertNull(escalationsMoreLine(3))
        assertEquals("2 more in Needs you", escalationsMoreLine(5))
    }

    @Test
    fun a_send_queues_only_where_the_screen_queues_and_only_mid_turn() {
        assertEquals(true, queuesSendNow(true, "working"))
        assertEquals(false, queuesSendNow(true, "idle"))
        assertEquals(false, queuesSendNow(false, "working"))
        assertEquals(false, queuesSendNow(true, null))
    }
}
