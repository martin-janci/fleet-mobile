package dev.claudefleet.mobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which of the app's screens is showing. */
sealed interface Screen {
    /**
     * The fleet list, optionally narrowed to one host — what tapping a host
     * row on the Hosts screen does. `null` is "no filter", not "unknown host";
     * every ordinary navigation to Sessions (a tab tap, app start) passes it.
     */
    data class Sessions(val hostAlias: String? = null) : Screen
    data class Session(val id: Long) : Screen
    data object Hosts : Screen
    data object Settings : Screen
}

/** The three destinations in the bottom bar. */
enum class Tab { Sessions, Hosts, Settings }

/**
 * Where the app is, as a small object rather than as Compose state.
 *
 * Deliberately not a `remember { mutableStateOf(...) }` inside the root
 * composable: nothing in this repo can render a screen, so state that lives
 * inside one is state nothing can assert. Everything here is a decision — what
 * back does on a tab, whether a session survives a trip to Settings — and each
 * one is a line in `NavigatorTest`.
 *
 * **Pair is not here.** The app is on the Pair screen exactly when it holds no
 * credential, which is `AuthState`'s business and not a place you navigate to;
 * the root reads the auth state and this navigator only describes where you are
 * once you are in.
 */
class Navigator {
    private val _screen = MutableStateFlow<Screen>(Screen.Sessions())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _tab = MutableStateFlow(tabOf(_screen.value))

    /**
     * Which tab is lit. A session belongs to the list it was opened from, so
     * the Sessions tab stays lit while one is open — a bar with nothing lit
     * reads as broken.
     *
     * Its own flow rather than a `map` of [screen], so that both are plain
     * `StateFlow`s a composable can read without a scope to share in.
     */
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Open one session's screen. */
    fun open(sessionId: Long) {
        go(Screen.Session(sessionId))
    }

    /**
     * Go back one step.
     *
     * Returns whether the app handled it. On a tab there is nowhere to go back
     * to inside the app, and saying so is what lets the Android host hand the
     * gesture to the system instead of swallowing it.
     */
    fun back(): Boolean {
        if (_screen.value !is Screen.Session) return false
        go(Screen.Sessions())
        return true
    }

    /**
     * Switch tabs. Leaves any open session behind rather than remembering it:
     * a session can be killed from the desktop while Settings is showing, and
     * "the app returns you to a session that no longer exists" is a worse
     * surprise than "the app returns you to the list". A host filter is the
     * same kind of state and leaves the same way — reselecting Sessions always
     * lands on the unfiltered list, never the one a host tap set up earlier.
     */
    fun select(tab: Tab) {
        go(
            when (tab) {
                Tab.Sessions -> Screen.Sessions()
                Tab.Hosts -> Screen.Hosts
                Tab.Settings -> Screen.Settings
            },
        )
    }

    /**
     * Jump to the Sessions tab filtered to one host — what tapping a host row
     * on the Hosts screen does. Goes through [go] like every other move, so
     * the tab indicator follows it there without a separate `select` call.
     */
    fun showSessionsFor(alias: String) {
        go(Screen.Sessions(hostAlias = alias))
    }

    private fun go(screen: Screen) {
        _screen.value = screen
        _tab.value = tabOf(screen)
    }
}

private fun tabOf(screen: Screen): Tab = when (screen) {
    is Screen.Sessions, is Screen.Session -> Tab.Sessions
    Screen.Hosts -> Tab.Hosts
    Screen.Settings -> Tab.Settings
}
