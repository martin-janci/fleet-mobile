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

/**
 * How much the app moves (review r11), the desktop's Settings → Appearance →
 * Motion on the phone. System follows the phone's own setting (Android's
 * animator scale at 0, iOS's Reduce Motion); Reduced keeps only fades. The
 * desktop's Off is Reduced here: the phone's loaders have no motion beyond it.
 */
enum class MotionChoice(val label: String) {
    SYSTEM("System"),
    FULL("Full"),
    REDUCED("Reduced"),
    ;

    /** What the app root provides as `LocalReducedMotion`: null follows the system. */
    val reduced: Boolean? get() = when (this) {
        SYSTEM -> null
        FULL -> false
        REDUCED -> true
    }
}

private const val THEME_PREF = "phone.theme"
private const val MOTION_PREF = "phone.motion"
private const val LOCK_PREF = "phone.lock"

/**
 * The settings that belong to this phone alone (redesign 14.11, "This phone"):
 * which notifications it posts, which theme it draws in, how much it moves,
 * and whether the fingerprint lock is on. Kept on the device, never sent to the hub, and
 * saved as they change; there is no Save.
 *
 * One instance for the app, held by `AppContainer`, so the theme switch
 * recolours every screen at once. The notification kinds are written through
 * to [Prefs] as well, because the Android service reads them from there.
 */
class PhoneSettings(private val prefs: Prefs) {
    private val _theme = MutableStateFlow(readTheme())
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _motion = MutableStateFlow(readMotion())
    val motion: StateFlow<MotionChoice> = _motion.asStateFlow()

    private val _notifyKinds = MutableStateFlow(prefs.notifyKinds())
    val notifyKinds: StateFlow<NotifyKinds> = _notifyKinds.asStateFlow()

    /** The fingerprint lock: asked when the app opens and before an answer to a session's question. */
    private val _lock = MutableStateFlow(prefs.getStringList(LOCK_PREF).firstOrNull() == "on")
    val lock: StateFlow<Boolean> = _lock.asStateFlow()

    /**
     * The lock was passed since the app opened. Held here, not saved: one
     * instance lives as long as the app's container, so a new start asks
     * again and a turned phone does not.
     */
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        prefs.putStringList(THEME_PREF, listOf(choice.name.lowercase()))
        _theme.value = choice
    }

    fun setMotion(choice: MotionChoice) {
        prefs.putStringList(MOTION_PREF, listOf(choice.name.lowercase()))
        _motion.value = choice
    }

    fun setNotify(kind: NotifyKind, on: Boolean) {
        val next = _notifyKinds.value.with(kind, on)
        prefs.writeNotifyKinds(next)
        _notifyKinds.value = next
    }

    /**
     * Turn the lock on or off — only after the check has just passed (the
     * caller asks first), so turning it on counts as this run's unlock.
     */
    fun setLock(on: Boolean) {
        prefs.putStringList(LOCK_PREF, listOf(if (on) "on" else "off"))
        _lock.value = on
        if (on) _unlocked.value = true
    }

    fun unlock() {
        _unlocked.value = true
    }

    private fun readTheme(): ThemeChoice {
        val stored = prefs.getStringList(THEME_PREF).firstOrNull()
        return ThemeChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: ThemeChoice.SYSTEM
    }

    private fun readMotion(): MotionChoice {
        val stored = prefs.getStringList(MOTION_PREF).firstOrNull()
        return MotionChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: MotionChoice.SYSTEM
    }
}
