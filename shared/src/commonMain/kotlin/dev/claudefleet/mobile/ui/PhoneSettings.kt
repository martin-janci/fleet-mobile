package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.notify.NotifyKind
import dev.claudefleet.mobile.notify.NotifyKinds
import dev.claudefleet.mobile.notify.notifyKinds
import dev.claudefleet.mobile.notify.writeNotifyKinds
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The theme This phone draws in. System follows the phone's own setting, as the app always has. */
enum class ThemeChoice(val label: String) {
    DARK("Dark"),
    LIGHT("Light"),
    SYSTEM("System"),
    ;

    /** Dark or not, given what the system says. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        DARK -> true
        LIGHT -> false
        SYSTEM -> systemDark
    }
}

private const val THEME_PREF = "phone.theme"

/**
 * The settings that belong to this phone alone (redesign 14.11, "This phone"):
 * which notifications it posts and which theme it draws in. Kept on the
 * device, never sent to the hub, and saved as they change; there is no Save.
 *
 * One instance for the app, held by `AppContainer`, so the theme switch
 * recolours every screen at once. The notification kinds are written through
 * to [Prefs] as well, because the Android service reads them from there.
 */
class PhoneSettings(private val prefs: Prefs) {
    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _notifyKinds = MutableStateFlow(prefs.notifyKinds())
    val notifyKinds: StateFlow<NotifyKinds> = _notifyKinds.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.putStringList(THEME_PREF, listOf(choice.name.lowercase()))
        _theme.value = choice
    }

    fun setNotify(kind: NotifyKind, on: Boolean) {
        val next = _notifyKinds.value.with(kind, on)
        prefs.writeNotifyKinds(next)
        _notifyKinds.value = next
    }

    private fun readTheme(): ThemeChoice {
        val stored = prefs.getStringList(THEME_PREF).firstOrNull()
        return ThemeChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: ThemeChoice.SYSTEM
    }
}
