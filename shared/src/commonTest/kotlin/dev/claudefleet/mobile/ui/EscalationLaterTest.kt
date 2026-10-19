package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LATER_MAX_AGE_SECS
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

    /** Review w21-1: a Later on a host-down card ends when the host answers again. */
    @Test
    fun a_host_that_recovers_ends_its_later_so_the_next_outage_is_a_card() {
        val prefs = FakePrefs()
        val later = EscalationLater(prefs) { 1_000L }
        later.putOff("host:mac")
        later.putOff("host:htz")
        later.putOff("2:waiting:4")
        later.forgetRecoveredHosts(listOf(HostRow(alias = "mac", reachable = true), HostRow(alias = "htz", reachable = false)))
        assertEquals(listOf("host:htz", "2:waiting:4"), later.later.value)
        assertEquals(listOf("host:htz", "2:waiting:4"), EscalationLater(prefs) { 1_000L }.later.value, "kept across a restart")
        // An empty host list (before the first read) forgets nothing.
        later.forgetRecoveredHosts(emptyList())
        assertEquals(listOf("host:htz", "2:waiting:4"), later.later.value)
    }

    @Test
    fun a_later_lapses_after_a_week() {
        val prefs = FakePrefs()
        EscalationLater(prefs) { 1_000L }.putOff("host:mac")
        assertEquals(listOf("host:mac"), EscalationLater(prefs) { 1_000L + LATER_MAX_AGE_SECS - 1 }.later.value)
        assertEquals(emptyList<String>(), EscalationLater(prefs) { 1_000L + LATER_MAX_AGE_SECS }.later.value)
    }

    @Test
    fun a_key_kept_before_laters_carried_a_time_still_reads_back() {
        val prefs = FakePrefs()
        prefs.putStringList(EscalationLater.KEY, listOf("2:waiting:4"))
        assertEquals(listOf("2:waiting:4"), EscalationLater(prefs) { 5L }.later.value)
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
