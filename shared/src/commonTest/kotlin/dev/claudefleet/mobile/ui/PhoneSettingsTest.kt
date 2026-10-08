package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.notify.NotifyKind
import dev.claudefleet.mobile.notify.notifyKinds
import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneSettingsTest {

    @Test
    fun the_theme_follows_the_system_until_one_is_picked_and_survives_a_restart() {
        val prefs = FakePrefs()
        val first = PhoneSettings(prefs)
        assertEquals(ThemeChoice.SYSTEM, first.theme.value)
        assertTrue(first.theme.value.isDark(systemDark = true))
        assertFalse(first.theme.value.isDark(systemDark = false))

        first.setTheme(ThemeChoice.LIGHT)
        assertEquals(ThemeChoice.LIGHT, first.theme.value)

        val again = PhoneSettings(prefs)
        assertEquals(ThemeChoice.LIGHT, again.theme.value, "read back from the device's store")
        assertFalse(again.theme.value.isDark(systemDark = true))
    }

    @Test
    fun an_unreadable_theme_falls_back_to_the_system() {
        val prefs = FakePrefs().apply { putStringList("phone.theme", listOf("sepia")) }
        assertEquals(ThemeChoice.SYSTEM, PhoneSettings(prefs).theme.value)
    }

    @Test
    fun a_notification_switch_is_written_where_the_service_reads_it() {
        val prefs = FakePrefs()
        val phone = PhoneSettings(prefs)

        phone.setNotify(NotifyKind.FAILED, on = false)

        assertFalse(phone.notifyKinds.value.allows(NotifyKind.FAILED))
        assertFalse(prefs.notifyKinds().allows("failed"), "the Android service reads Prefs, not this object")
        assertTrue(prefs.notifyKinds().allows("waiting"))
    }
}
