package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotifyKindsTest {

    @Test
    fun failed_and_stop_failed_are_failed_and_everything_else_waits_on_a_person() {
        assertEquals(NotifyKind.FAILED, notifyKindOf("failed"))
        assertEquals(NotifyKind.FAILED, notifyKindOf("stop_failed"))
        for (reason in listOf("waiting", "stuck", "lifecycle", "context_full", "ci_failing", null)) {
            assertEquals(NotifyKind.NEEDS_YOU, notifyKindOf(reason), "$reason")
        }
    }

    @Test
    fun everything_is_on_until_a_kind_is_turned_off() {
        val prefs = FakePrefs()
        assertEquals(NotifyKinds(), prefs.notifyKinds())
        assertTrue(prefs.notifyKinds().allows("waiting"))
        assertTrue(prefs.notifyKinds().allows("failed"))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.NEEDS_YOU, on = false))

        assertFalse(prefs.notifyKinds().allows("waiting"))
        assertTrue(prefs.notifyKinds().allows("failed"))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.NEEDS_YOU, on = true))
        assertEquals(NotifyKinds(), prefs.notifyKinds())
    }

    @Test
    fun a_stored_kind_this_build_does_not_know_is_ignored() {
        val prefs = FakePrefs()
        prefs.putStringList("phone.notify.off", listOf("QUIET", "FAILED"))
        assertEquals(NotifyKinds(setOf(NotifyKind.FAILED, NotifyKind.DONE)), prefs.notifyKinds())
    }

    /** 14.11: Done starts off — a finished session asks nothing — and is announced once turned on. */
    @Test
    fun done_starts_off_and_stays_on_once_turned_on() {
        val prefs = FakePrefs()
        assertEquals(NotifyKind.DONE, notifyKindOf(DONE_REASON))
        assertFalse(prefs.notifyKinds().allows(DONE_REASON))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.DONE, on = true))
        assertTrue(prefs.notifyKinds().allows(DONE_REASON))
        assertTrue(prefs.notifyKinds().allows("waiting"))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.FAILED, on = false))
        assertTrue(prefs.notifyKinds().allows(DONE_REASON), "turning another kind off keeps Done on")
        assertFalse(prefs.notifyKinds().allows("failed"))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.DONE, on = false))
        assertFalse(prefs.notifyKinds().allows(DONE_REASON))
    }
}
