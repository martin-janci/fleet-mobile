package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hub's notifications matrix and quiet hours on the phone (claude-fleet 11.9). */
class FleetNotifyTest {

    private val defaults = mapOf(
        NOTIFY_PHONE to "needs_you,failed,routine_failed",
        NOTIFY_QUIET_HOURS to "22:00-07:30",
        NOTIFY_QUIET_EXCEPT to "failed",
    )

    @Test
    fun quiet_hours_read_as_the_hub_reads_them() {
        assertEquals(1320 to 450, parseQuietHours("22:00-07:30"))
        assertEquals(545 to 1020, parseQuietHours(" 9:05 - 17:00 "))
        for (bad in listOf("", "22:00", "24:00-07:00", "22:60-07:00", "7-8", "08:00-08:00", "a:bc-07:00")) {
            assertNull(parseQuietHours(bad), bad)
        }
    }

    @Test
    fun a_range_may_run_past_midnight_and_its_end_is_outside() {
        val night = parseQuietHours("22:00-07:30")
        assertTrue(inQuietHours(night, 23 * 60))
        assertTrue(inQuietHours(night, 3 * 60))
        assertFalse(inQuietHours(night, 7 * 60 + 30))
        assertFalse(inQuietHours(night, 12 * 60))
        assertTrue(inQuietHours(parseQuietHours("09:00-17:00"), 12 * 60))
        assertFalse(inQuietHours(null, 12 * 60))
    }

    @Test
    fun reasons_land_on_the_matrix_rows_as_the_desktop_files_them() {
        assertEquals("needs_you", notifyStateOf("waiting"))
        assertEquals("needs_you", notifyStateOf(null))
        assertEquals("failed", notifyStateOf("failed"))
        assertEquals("failed", notifyStateOf("stop_failed"))
        assertEquals("done", notifyStateOf(DONE_REASON))
        for (r in listOf("stuck", "host_down", "account_limit", "no_credentials", "context_full")) {
            assertEquals("blocked", notifyStateOf(r), r)
        }
    }

    @Test
    fun the_phone_column_decides_and_quiet_hours_let_only_the_exceptions_through() {
        val f = FleetNotify.of(defaults)!!
        val noon = 12 * 60
        val night = 23 * 60
        assertTrue(f.allows("waiting", noon))
        assertFalse(f.allows("stuck", noon), "Blocked is not in the Phone column by default")
        assertFalse(f.allows(DONE_REASON, noon))
        assertFalse(f.allows("waiting", night))
        assertTrue(f.allows("failed", night))
        assertTrue(FleetNotify.of(defaults + (NOTIFY_QUIET_HOURS to ""))!!.allows("waiting", night))
        assertNull(FleetNotify.of(emptyMap()), "a hub before 11.9 has no matrix")
    }

    @Test
    fun the_last_matrix_read_is_remembered_and_this_phones_switches_still_apply() {
        val prefs = FakePrefs()
        assertNull(prefs.fleetNotify())
        assertTrue(prefs.notifyAllows("stuck", 12 * 60), "no matrix read yet: only This phone decides")

        prefs.writeFleetNotify(defaults + ("work.recent_days" to "14"))
        assertEquals(FleetNotify.of(defaults), prefs.fleetNotify())
        assertFalse(prefs.notifyAllows("stuck", 12 * 60))
        assertFalse(prefs.notifyAllows("waiting", 23 * 60))
        assertTrue(prefs.notifyAllows("failed", 23 * 60))

        prefs.writeNotifyKinds(prefs.notifyKinds().with(NotifyKind.FAILED, on = false))
        assertFalse(prefs.notifyAllows("failed", 23 * 60), "This phone's switch is off")

        prefs.writeFleetNotify(mapOf("work.recent_days" to "14"))
        assertNull(prefs.fleetNotify(), "a hub without the matrix clears the old one")
    }
}
