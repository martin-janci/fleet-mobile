package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G7.18 (MobileSettings · This phone): quiet hours set on the phone itself, on top of the fleet's. */
class PhoneQuietTest {

    @Test
    fun off_until_set_and_kept_across_reads() {
        val prefs = FakePrefs()
        assertEquals(PhoneQuiet(), prefs.phoneQuiet())
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT, needsYouThrough = true))
        assertEquals(PhoneQuiet(22 * 60 to 7 * 60, needsYouThrough = true), prefs.phoneQuiet())
        assertEquals("22:00-07:00", prefs.phoneQuiet().text)
        prefs.writePhoneQuiet(PhoneQuiet())
        assertNull(prefs.phoneQuiet().range)
    }

    @Test
    fun quiet_hours_on_the_phone_hold_back_every_alert_but_needs_you_when_asked() {
        val prefs = FakePrefs()
        val night = 23 * 60
        val noon = 12 * 60
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT))
        assertFalse(prefs.notifyAllows("waiting", night))
        assertFalse(prefs.notifyAllows("failed", night))
        assertTrue(prefs.notifyAllows("waiting", noon))
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT, needsYouThrough = true))
        assertTrue(prefs.notifyAllows("waiting", night))
        assertFalse(prefs.notifyAllows("failed", night))
    }

    @Test
    fun the_fleet_still_decides_first() {
        val prefs = FakePrefs()
        // The fleet holds back Needs you at night; the phone letting it through cannot overrule that.
        prefs.writeFleetNotify(mapOf(NOTIFY_PHONE to "needs_you,failed", NOTIFY_QUIET_HOURS to "22:00-07:00", NOTIFY_QUIET_EXCEPT to ""))
        prefs.writePhoneQuiet(PhoneQuiet(PhoneQuiet.NIGHT, needsYouThrough = true))
        assertFalse(prefs.notifyAllows("waiting", 23 * 60))
    }

    @Test
    fun clock_times_read_like_each_end_of_the_hubs_range() {
        assertEquals(7 * 60 + 5, parseClock("07:05"))
        assertEquals(7 * 60 + 5, parseClock(" 7:05 "))
        assertEquals(0, parseClock("00:00"))
        for (bad in listOf("", "7", "24:00", "07:60", "7:5", "ab:cd")) assertNull(parseClock(bad), bad)
        assertEquals("07:05", clockText(7 * 60 + 5))
        assertEquals("00:00", clockText(24 * 60))
    }
}
