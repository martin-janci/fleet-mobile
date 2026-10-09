package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.connectionReason
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.ui.components.statusStripText
import dev.claudefleet.mobile.ui.kit.PhoneConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Review round 13: the phone's error, empty, offline and stale states, as
 * the words each one decides on (MobileStates, MobileRecovery, plan 14.12).
 */
class PhoneStatesR13Test {

    /** P13-1: "Nothing needs you" is a claim only a live hub can make. */
    @Test
    fun the_inbox_says_nothing_needs_you_only_while_live() {
        assertEquals("Nothing needs you.", inboxEmptyText(PhoneConnection.Live))
        for (c in listOf(PhoneConnection.Reconnecting(1), PhoneConnection.Offline(null), PhoneConnection.Refused("old hub"))) {
            val said = inboxEmptyText(c)
            assertFalse(said.startsWith("Nothing needs you"), "$c: $said")
        }
        assertTrue(inboxEmptyText(PhoneConnection.Offline("x")).startsWith("Not connected"))
        assertTrue(inboxEmptyText(PhoneConnection.Reconnecting(2)).startsWith("Connecting"))
    }

    /** P13-3: the first attempt is quiet, later ones the well, and past 6 s a static "can't reach" with Retry. */
    @Test
    fun a_cold_start_stops_spinning_once_the_hub_is_lost() {
        assertEquals(ColdStart.Connecting, coldStartBody(PhoneConnection.Reconnecting(1)))
        assertEquals(ColdStart.Reconnecting, coldStartBody(PhoneConnection.Reconnecting(3)))
        assertEquals(ColdStart.Unreachable("Can't reach the hub from this network."), coldStartBody(PhoneConnection.Offline("Can't reach the hub from this network.")))
        assertEquals(ColdStart.Unreachable("too new"), coldStartBody(PhoneConnection.Refused("too new")))
    }

    /** P13-4: the banner's sentence names no exception class and no status; those are Details'. */
    @Test
    fun a_connection_reason_is_a_sentence() {
        val failures = listOf(
            HubError.Transport(RuntimeException("connection reset")),
            HubError.Http(502, "<html>bad gateway</html>"),
            HubError.Tool("E_INTERNAL", "boom"),
            IllegalStateException("Authorization: Bearer 0123"),
        )
        for (t in failures) {
            val said = connectionReason(t)
            assertTrue(said.first().isUpperCase() && said.endsWith("."), said)
            for (leak in listOf("Exception", "HTTP", "502", "E_", "Bearer", "reset")) {
                assertFalse(leak in said, "$t leaked $leak: $said")
            }
        }
    }

    /** P13-7: a failed status read is not "not running", and a down hub is said to be down. */
    @Test
    fun control_says_what_is_known() {
        val base = ControlUiState(available = true, known = true)
        assertEquals("Control is not running. Wake it to start the chat.", controlWaitingLine(base))
        assertEquals("Couldn't read Control's state.", controlWaitingLine(base.copy(statusFailed = true)))
        assertFalse(base.copy(statusFailed = true).canWake, "nothing is known, so nothing is offered to wake")
        assertTrue(controlWaitingLine(base.copy(connected = false)).startsWith("Not connected"))
        assertFalse(base.copy(connected = false).canWake)
        // The hub's own word for a state this build does not know stays off the screen.
        val odd = controlBlockedLine("quota_frozen", null)
        assertFalse("quota_frozen" in odd, odd)
    }

    /** P13-8: without the stream a working session is "was working", and its clock stops. */
    @Test
    fun an_offline_header_stops_counting() {
        val row = SessionRow(id = 1, tmuxName = "s", claudeStatus = "working", lastTurnAt = 1_000)
        assertEquals("working 2 min", statusStripText(row, null, nowSeconds = 1_134))
        assertEquals("was working · no live updates", statusStripText(row, null, nowSeconds = 1_134, live = false))
        assertEquals(
            statusStripText(row, null, nowSeconds = 1_134, live = false),
            statusStripText(row, null, nowSeconds = 9_999, live = false),
            "the clock does not run while nothing is live",
        )
        // Idle is a fact that does not go stale the same way: unchanged.
        val idle = row.copy(claudeStatus = "idle", lastStopAt = 1_000)
        assertEquals(statusStripText(idle, null, 8_200), statusStripText(idle, null, 8_200, live = false))
    }

    /** P13-9: a scan that failed does not say "None new". */
    @Test
    fun a_failed_scan_is_not_none_new() {
        assertEquals("Couldn't read the hub's SSH config", addHostMeta(AddHostUiState(scanFailed = true)))
    }
}
