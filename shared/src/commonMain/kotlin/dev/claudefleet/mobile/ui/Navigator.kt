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

    /**
     * Where [back] returns to from an open session — the Sessions screen as
     * it stood, filter and all, the moment [open] was called. Not a field on
     * [Screen.Session] itself: a session's identity is just its id, and
     * carrying a filter it has nothing to do with would make two opens of
     * the same session unequal depending on how you got there.
     */
    private var returnTo: Screen.Sessions = Screen.Sessions()

    /** Open one session's screen. */
    fun open(sessionId: Long) {
        (_screen.value as? Screen.Sessions)?.let { returnTo = it }
        go(Screen.Session(sessionId))
    }

    /**
     * Go back one step.
     *
     * Returns whether the app handled it. On a tab there is nowhere to go back
     * to inside the app, and saying so is what lets the Android host hand the
     * gesture to the system instead of swallowing it.
     *
     * Restores [returnTo] rather than a bare `Screen.Sessions()`, so a session
     * opened from a host-filtered list comes back to that same filter instead
     * of silently clearing it. Only a tab reselect or the filter's own clear
     * chip may drop it — not the unrelated act of looking at a session and
     * returning.
     */
    fun back(): Boolean {
        if (_screen.value !is Screen.Session) return false
        go(returnTo)
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

    /**
     * Clear the current host filter — the Sessions bar's own clear-chip
     * action. A no-op unless the current screen actually is a Sessions
     * screen; there is nothing to clear from an open session, Hosts, or
     * Settings.
     *
     * This is [Screen] itself, not [SessionsViewModel]'s filter: the review
     * fix this closes found that the chip used to call `setHostFilter(null)`
     * directly on the view model, leaving [screen] still holding the old
     * `Screen.Sessions(alias)`. [open] reads `_screen.value` to build
     * [returnTo], so it captured the stale filter, and [back] restored it —
     * the filter came back the moment a session was opened and closed. With
     * one source of truth for the filter (this screen, not a second copy in
     * the view model), [open] can only ever capture what this actually set.
     */
    fun clearHostFilter() {
        if (_screen.value is Screen.Sessions) go(Screen.Sessions())
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
