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

    /** 14.11: the lock is kept on the device; turning it on counts as this run's unlock, a new start asks again. */
    @Test
    fun the_lock_survives_a_restart_and_is_asked_again_there() {
        val prefs = FakePrefs()
        val first = PhoneSettings(prefs)
        assertFalse(first.lock.value)
        assertFalse(first.unlocked.value)

        first.setLock(true)
        assertTrue(first.lock.value)
        assertTrue(first.unlocked.value, "the check that turned it on was this run's unlock")

        val again = PhoneSettings(prefs)
        assertTrue(again.lock.value, "read back from the device's store")
        assertFalse(again.unlocked.value, "a new start asks again")
        assertTrue(lockShown(again.lock.value, again.unlocked.value, available = true))
        again.unlock()
        assertFalse(lockShown(again.lock.value, again.unlocked.value, available = true))

        again.setLock(false)
        assertFalse(PhoneSettings(prefs).lock.value)
    }

    @Test
    fun a_phone_that_cannot_ask_is_never_locked_out() {
        assertFalse(lockShown(lockOn = true, unlocked = false, available = false))
        assertFalse(lockShown(lockOn = false, unlocked = false, available = true))
        assertTrue(answerAsksFirst(lockOn = true, available = true))
        assertFalse(answerAsksFirst(lockOn = true, available = false))
    }

    @Test
    fun an_answer_waits_for_the_check_only_while_the_lock_is_on() {
        val gate = object : BiometricGate {
            var passes = false
            val asked = mutableListOf<String>()
            override val available = true
            override fun ask(reason: String, onResult: (Boolean) -> Unit) {
                asked += reason
                onResult(passes)
            }
        }
        var sent = 0
        gate.guard(lockOn = false, reason = "Answer") { sent++ }
        assertEquals(1, sent)
        assertTrue(gate.asked.isEmpty(), "no lock, no question")

        gate.guard(lockOn = true, reason = "Answer") { sent++ }
        assertEquals(1, sent, "a failed check sends nothing")
        gate.passes = true
        gate.guard(lockOn = true, reason = "Answer") { sent++ }
        assertEquals(2, sent)
        assertEquals(listOf("Answer", "Answer"), gate.asked)
    }

    @Test
    fun this_phones_row_names_the_lock_where_the_phone_has_one() {
        assertEquals("Notifications, theme", thisPhoneLine(updates = false, lock = false))
        assertEquals("Notifications, theme, updates, lock", thisPhoneLine(updates = true, lock = true))
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
