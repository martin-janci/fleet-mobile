package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.notify.NotifyKind
import dev.claudefleet.mobile.notify.NotifyKinds
import dev.claudefleet.mobile.notify.PhoneQuiet
import dev.claudefleet.mobile.notify.notifyKinds
import dev.claudefleet.mobile.notify.phoneQuiet
import dev.claudefleet.mobile.notify.writeNotifyKinds
import dev.claudefleet.mobile.notify.writePhoneQuiet
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.ui.theme.ContrastLevel
import dev.claudefleet.mobile.ui.theme.LocalClock
import dev.claudefleet.mobile.ui.theme.OrbitAccent
import dev.claudefleet.mobile.ui.theme.OrbitPalette
import dev.claudefleet.mobile.ui.theme.localClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The theme this phone draws in. Sun (the default, as on the desktop) is light
 * while the sun is up in the phone's time zone and dark after sunset, easing through
 * golden hour, blue hour and night ([OrbitPalette]). System follows the
 * phone's own light/dark setting; Light and Dark hold noon and a mid-night.
 */
enum class ThemeChoice(val label: String) {
    SUN("Sun"),
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    /** The sun height the theme paints at, given the system setting and the clock. */
    fun sun(systemDark: Boolean, clock: () -> LocalClock = ::localClock): Double = when (this) {
        SUN -> OrbitPalette.sunNow(clock())
        SYSTEM -> if (systemDark) OrbitPalette.DARK_SUN else OrbitPalette.LIGHT_SUN
        LIGHT -> OrbitPalette.LIGHT_SUN
        DARK -> OrbitPalette.DARK_SUN
    }

    /** Dark or not, given what the system says (and, for Sun, the clock). */
    fun isDark(systemDark: Boolean, clock: () -> LocalClock = ::localClock): Boolean = sun(systemDark, clock) < 0
}

/** Contrast, the desktop's levels. Text is held at the same contrast all day; High is for glare. */
enum class ContrastChoice(val label: String, val level: ContrastLevel) {
    SOFT("Soft", ContrastLevel.SOFT),
    STANDARD("Standard", ContrastLevel.STD),
    HIGH("High", ContrastLevel.HIGH),
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
private const val CONTRAST_PREF = "phone.contrast"
private const val ACCENT_PREF = "phone.accent"
private const val LOCK_PREF = "phone.lock"
private const val WORK_TAP_PREF = "phone.work_tap"

/**
 * The settings that belong to this phone alone (redesign 14.11, "This phone"):
 * which notifications it posts and its quiet hours, which theme it draws in,
 * how much it moves, whether the fingerprint lock is on, and what a tap on a
 * Work row opens. Kept on the device, never sent to the hub, and
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

    private val _contrast = MutableStateFlow(read(CONTRAST_PREF, ContrastChoice.entries, ContrastChoice.STANDARD))
    val contrast: StateFlow<ContrastChoice> = _contrast.asStateFlow()

    private val _accent = MutableStateFlow(read(ACCENT_PREF, OrbitAccent.entries, OrbitAccent.CYAN))
    val accent: StateFlow<OrbitAccent> = _accent.asStateFlow()

    private val _notifyKinds = MutableStateFlow(prefs.notifyKinds())
    val notifyKinds: StateFlow<NotifyKinds> = _notifyKinds.asStateFlow()

    /** This phone's own quiet hours, on top of the fleet's; written through to [Prefs] for the service, like the kinds. */
    private val _quiet = MutableStateFlow(prefs.phoneQuiet())
    val quiet: StateFlow<PhoneQuiet> = _quiet.asStateFlow()

    /** The fingerprint lock: asked when the app opens and before an answer to a session's question. */
    private val _lock = MutableStateFlow(prefs.getStringList(LOCK_PREF).firstOrNull() == "on")
    val lock: StateFlow<Boolean> = _lock.asStateFlow()

    /** What tapping a Work row opens: its ticket (the default) or its live session. */
    private val _workTap = MutableStateFlow(readWorkTap())
    val workTap: StateFlow<WorkTapChoice> = _workTap.asStateFlow()

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

    fun setContrast(choice: ContrastChoice) {
        prefs.putStringList(CONTRAST_PREF, listOf(choice.name.lowercase()))
        _contrast.value = choice
    }

    fun setAccent(choice: OrbitAccent) {
        prefs.putStringList(ACCENT_PREF, listOf(choice.name.lowercase()))
        _accent.value = choice
    }

    fun setMotion(choice: MotionChoice) {
        prefs.putStringList(MOTION_PREF, listOf(choice.name.lowercase()))
        _motion.value = choice
    }

    fun setWorkTap(choice: WorkTapChoice) {
        prefs.putStringList(WORK_TAP_PREF, listOf(choice.name.lowercase()))
        _workTap.value = choice
    }

    fun setNotify(kind: NotifyKind, on: Boolean) {
        val next = _notifyKinds.value.with(kind, on)
        prefs.writeNotifyKinds(next)
        _notifyKinds.value = next
    }

    fun setQuiet(quiet: PhoneQuiet) {
        prefs.writePhoneQuiet(quiet)
        _quiet.value = quiet
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
        return ThemeChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: ThemeChoice.SUN
    }

    private fun <T : Enum<T>> read(key: String, entries: List<T>, fallback: T): T {
        val stored = prefs.getStringList(key).firstOrNull()
        return entries.firstOrNull { it.name.lowercase() == stored } ?: fallback
    }

    private fun readWorkTap(): WorkTapChoice {
        val stored = prefs.getStringList(WORK_TAP_PREF).firstOrNull()
        return WorkTapChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: WorkTapChoice.TICKET
    }

    private fun readMotion(): MotionChoice {
        val stored = prefs.getStringList(MOTION_PREF).firstOrNull()
        return MotionChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: MotionChoice.SYSTEM
    }
}
