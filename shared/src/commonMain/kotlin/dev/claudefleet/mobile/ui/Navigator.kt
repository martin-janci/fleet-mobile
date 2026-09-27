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

    /**
     * The New session form, pushed over the list it was opened from.
     * [hostAlias] is that list's host filter — the form's first guess at a host.
     */
    data class NewSession(
        val hostAlias: String? = null,
        /** Ticket mode (M8.4): start work on this key rather than a plain session. */
        val ticketKey: String? = null,
    ) : Screen
    data object Hosts : Screen
    data object Settings : Screen

    /** The *My work* tab (claude-fleet M14): org → group → task. */
    data object Work : Screen

    /** One task of the Work view, `item:<id>` or `ref:<KEY>`, pushed over what opened it. */
    data class Task(val taskId: String) : Screen
}

/**
 * The destinations in the bottom bar. [Work] is drawn only when the hub's
 * schema lists `work { tree }`; see `HubCapabilities.workView`.
 */
enum class Tab(val label: String) {
    Sessions("Sessions"),
    Work("My work"),
    Hosts("Hosts"),
    Settings("Settings"),
}

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
     * Where [back] returns to, newest last: the screens a pushed screen was
     * opened over. Empty on a tab's own screen — that is what makes [back]
     * answer false there. A list is kept as it stood, filter and all, so a
     * session opened from a host-filtered list comes back to that filter.
     *
     * Not a field on [Screen.Session] itself: a session's identity is just
     * its id, and carrying where it was opened from would make two opens of
     * the same session unequal depending on how you got there.
     */
    private val stack = mutableListOf<Screen>()

    /** Open one session's screen. */
    fun open(sessionId: Long) {
        val current = _screen.value
        if (current == Screen.Session(sessionId)) return
        when (current) {
            // The form is never a place to come back to: the list under it is.
            is Screen.NewSession -> Unit
            // A call that finished after the person went to Hosts or
            // Settings: the session belongs to the fleet list.
            Screen.Hosts, Screen.Settings -> {
                stack.clear()
                stack += Screen.Sessions()
            }
            else -> stack += current
        }
        go(Screen.Session(sessionId))
    }

    /**
     * Open one task of the Work view, over whatever showed it: the *My
     * work* tab, another task, or a session's *Tasks* section.
     */
    fun openTask(taskId: String) {
        val current = _screen.value
        if (current == Screen.Task(taskId)) return
        when (current) {
            is Screen.NewSession -> return
            Screen.Hosts, Screen.Settings, is Screen.Sessions -> {
                stack.clear()
                stack += current
            }
            else -> stack += current
        }
        go(Screen.Task(taskId))
    }

    /**
     * Open the New session form over the list. Only from the list (or, in ticket mode, a task): that is
     * where the button is, and where [back] and the created session's own back
     * both return to.
     *
     * The form itself is never a place to come back to. [open] from it leaves
     * the list underneath, so backing out of the session it just created
     * lands on the list rather than on a form that would make a second one.
     *
     * [ticketKey] is the Tickets sheet's **Start here**: the same form, in
     * ticket mode, and [created] opens what it makes exactly as for a plain
     * session.
     */
    fun newSession(ticketKey: String? = null) {
        val current = _screen.value
        val host = when (current) {
            is Screen.Sessions -> current.hostAlias
            // A task's **Start here** (M14.4): the same form in ticket mode.
            is Screen.Task -> if (ticketKey == null) return else null
            else -> return
        }
        stack += current
        go(Screen.NewSession(hostAlias = host, ticketKey = ticketKey))
    }

    /**
     * The form's create finished: show the new session — if the form is still
     * what is showing. The call outlives the form (see
     * `NewSessionViewModel.callScope`), so it can finish after the person has
     * backed out or switched tabs, and yanking them into a session then would
     * be a surprise. The session is on the list either way.
     */
    fun created(sessionId: Long) {
        if (_screen.value is Screen.NewSession) open(sessionId)
    }

    /**
     * Go back one step.
     *
     * Returns whether the app handled it. On a tab there is nowhere to go back
     * to inside the app, and saying so is what lets the Android host hand the
     * gesture to the system instead of swallowing it.
     *
     * Restores the screen underneath rather than a bare `Screen.Sessions()`, so a session
     * opened from a host-filtered list comes back to that same filter instead
     * of silently clearing it. Only a tab reselect or the filter's own clear
     * chip may drop it — not the unrelated act of looking at a session and
     * returning.
     */
    fun back(): Boolean {
        val previous = stack.removeLastOrNull() ?: return false
        go(previous)
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
        stack.clear()
        go(
            when (tab) {
                Tab.Sessions -> Screen.Sessions()
                Tab.Work -> Screen.Work
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
        stack.clear()
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
     * its `returnTo`, so it captured the stale filter, and [back] restored it —
     * the filter came back the moment a session was opened and closed. With
     * one source of truth for the filter (this screen, not a second copy in
     * the view model), [open] can only ever capture what this actually set.
     */
    fun clearHostFilter() {
        if (_screen.value is Screen.Sessions) go(Screen.Sessions())
    }

    private fun go(screen: Screen) {
        _screen.value = screen
        // A pushed screen belongs to the tab at the bottom of the stack: a
        // session opened from *My work* keeps *My work* lit.
        _tab.value = tabOf(stack.firstOrNull() ?: screen)
    }
}

private fun tabOf(screen: Screen): Tab = when (screen) {
    is Screen.Sessions, is Screen.Session, is Screen.NewSession -> Tab.Sessions
    Screen.Work, is Screen.Task -> Tab.Work
    Screen.Hosts -> Tab.Hosts
    Screen.Settings -> Tab.Settings
}
