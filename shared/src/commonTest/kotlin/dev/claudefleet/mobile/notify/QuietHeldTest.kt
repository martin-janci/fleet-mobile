package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review w11-12: what quiet hours held back is said once, in one summary, when they end. */
class QuietHeldTest {
    private val night = 23 * 60
    private val morning = 7 * 60 + 1

    @Test
    fun only_quiet_hours_hold_an_alert_back_for_the_summary() {
        val prefs = FakePrefs()
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT))
        assertTrue(prefs.heldByQuietHours("waiting", night))
        assertFalse(prefs.heldByQuietHours("waiting", 12 * 60), "posted, not held")
        // This phone's switch says no at any hour: nothing to say later.
        prefs.writeNotifyKinds(NotifyKinds().with(NotifyKind.NEEDS_YOU, on = false))
        assertFalse(prefs.heldByQuietHours("waiting", night))
        // Nor does the fleet's matrix row that leaves the state out.
        prefs.writeNotifyKinds(NotifyKinds())
        prefs.writeFleetNotify(mapOf(NOTIFY_PHONE to "failed", NOTIFY_QUIET_HOURS to "", NOTIFY_QUIET_EXCEPT to ""))
        assertFalse(prefs.heldByQuietHours("waiting", night))
        // The fleet's own quiet hours hold too.
        prefs.writePhoneQuiet(PhoneQuiet())
        prefs.writeFleetNotify(mapOf(NOTIFY_PHONE to "needs_you", NOTIFY_QUIET_HOURS to "22:00-07:00", NOTIFY_QUIET_EXCEPT to ""))
        assertTrue(prefs.heldByQuietHours("waiting", night))
    }

    @Test
    fun the_summary_counts_what_still_waits_once_quiet_hours_end() {
        val prefs = FakePrefs()
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT))
        listOf(1L, 2L, 3L, 3L).forEach(prefs::holdForQuiet)
        val waiting = mapOf(1L to "waiting", 2L to "permission", 3L to null)
        assertNull(prefs.quietSummaryDue(waiting, night), "still quiet: nothing yet")
        assertEquals(setOf(1L, 2L), prefs.quietHeld(), "one that stopped waiting is forgotten")
        assertEquals(2, prefs.quietSummaryDue(waiting, morning))
        assertEquals(emptySet(), prefs.quietHeld())
        assertNull(prefs.quietSummaryDue(waiting, morning + 1), "said once")
        assertEquals("2 sessions need you", quietSummaryTitle(2))
        assertEquals("1 session needs you", quietSummaryTitle(1))
    }

    @Test
    fun nothing_held_still_waiting_says_nothing() {
        val prefs = FakePrefs()
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT))
        prefs.holdForQuiet(4L)
        assertNull(prefs.quietSummaryDue(mapOf(4L to null), morning))
        assertEquals(emptySet(), prefs.quietHeld())
    }

    @Test
    fun another_hub_forgets_what_was_held() {
        val prefs = FakePrefs()
        prefs.keepSeenForHub("https://a.example")
        prefs.holdForQuiet(5L)
        assertTrue(prefs.keepSeenForHub("https://b.example"))
        assertEquals(emptySet(), prefs.quietHeld())
    }
}
