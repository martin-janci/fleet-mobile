package dev.claudefleet.mobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    /** The Work view (claude-fleet M14): org → group → task. A tab of its own. */
    data object Work : Screen

    /** One task and all of its sessions, pushed over the Work view (or a session's Tasks). */
    data class Task(val taskId: String) : Screen

    /** The hub's copies of files sessions sent (claude-fleet file downloads). A tab of its own. */
    data object Files : Screen
}

/**
 * The destinations in the bottom bar. [Work] is drawn only when the hub
 * serves the Work view (`work { tree }`), and [Files] only when it keeps
 * downloads (`list_downloads`) — see `App.kt`.
 */
enum class Tab { Sessions, Work, Files, Hosts, Settings }

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
    /**
     * Everything this navigator knows, as one value: which screen, which tab
     * is lit, and where back goes. Every move is one [MutableStateFlow.update]
     * of it — a compare-and-set that retries on contention — so two moves at
     * once (a tap on the main thread, a view model's callback from a
     * background dispatcher as a write lands) can neither lose one another
     * nor leave the history half edited. [screen] and [tab] are then published
     * from it ([publish]).
     */
    private data class NavState(
        val screen: Screen = Screen.Sessions(),
        val tab: Tab = Tab.Sessions,
        /**
         * Where [back] returns to, most recent last — the screens a pushed one
         * (a session, the form, a task) was opened *from*, each as it stood: the
         * Sessions screen filter and all, the Work view, or a task. Task → session
         * → back lands on the task, and back again on the Work view.
         *
         * Only places are remembered, never a pushed screen that is merely being
         * replaced: opening a session from another session, or the session the
         * New session form just created, keeps what is under it. The form itself
         * is never a place to come back to — backing out of the session it made
         * lands under the form, not on a form that would make a second one.
         *
         * Not a field on [Screen.Session] itself: a session's identity is just
         * its id, and carrying where it was opened from would make two opens of
         * the same session unequal depending on how you got there. Bounded, so a
         * walk task → session → task → … cannot grow it for ever.
         */
        val history: List<Screen> = emptyList(),
    )

    private val nav = MutableStateFlow(NavState())

    private val _screen = MutableStateFlow<Screen>(Screen.Sessions())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _tab = MutableStateFlow(Tab.Sessions)

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
     * Remember [screen] to come back to, if it is a place rather than a
     * pushed screen being replaced. A session counts as a place only for a
     * task opened from its *Tasks* ([sessionIsAPlace]): back from the task
     * returns to that session.
     */
    private fun NavState.pushing(screen: Screen, sessionIsAPlace: Boolean = false): NavState {
        if (screen is Screen.NewSession) return this
        if (screen is Screen.Session && !sessionIsAPlace) return this
        return copy(history = (history + screen).takeLast(MAX_HISTORY))
    }

    /** Open one session's screen. */
    fun open(sessionId: Long) = move { it.pushing(it.screen).going(Screen.Session(sessionId)) }

    /**
     * Open one task's screen (the Work view): from the Work view, from a
     * session's *Tasks*, or from another task. Not from the form, which is
     * about a session still being made.
     */
    fun openTask(taskId: String) = move { s ->
        val current = s.screen
        when {
            current is Screen.NewSession || taskId.isBlank() -> s
            current == Screen.Task(taskId) -> s
            else -> s.pushing(current, sessionIsAPlace = true).going(Screen.Task(taskId))
        }
    }

    /**
     * Open the New session form over the list — or, in ticket mode, over a
     * task's screen (its **Start here**). Only from those two: that is where
     * the buttons are, and where [back] and the created session's own back
     * both return to.
     *
     * The form itself is never a place to come back to. [open] from it keeps
     * what was under it, so backing out of the session it just created lands
     * on the list (or the task) rather than on a form that would make a
     * second one.
     *
     * [ticketKey] is the Tickets sheet's or a task's **Start here**: the same
     * form, in ticket mode, and [created] opens what it makes exactly as for
     * a plain session.
     */
    fun newSession(ticketKey: String? = null) = move { s ->
        val current = s.screen
        val host = when (current) {
            is Screen.Sessions -> current.hostAlias
            is Screen.Task -> if (ticketKey != null) null else return@move s
            else -> return@move s
        }
        s.pushing(current).going(Screen.NewSession(hostAlias = host, ticketKey = ticketKey))
    }

    /**
     * The form's create finished: show the new session — if the form is still
     * what is showing. The call outlives the form (see
     * `NewSessionViewModel.callScope`), so it can finish after the person has
     * backed out or switched tabs, and yanking them into a session then would
     * be a surprise. The session is on the list either way. The check and the
     * move are one step, so a back racing it cannot be undone by it.
     */
    fun created(sessionId: Long) = move { s ->
        if (s.screen is Screen.NewSession) s.pushing(s.screen).going(Screen.Session(sessionId)) else s
    }

    /**
     * Go back one step.
     *
     * Returns whether the app handled it. On a tab there is nowhere to go back
     * to inside the app, and saying so is what lets the Android host hand the
     * gesture to the system instead of swallowing it.
     *
     * Restores the screen the pushed one was opened from rather than a bare
     * `Screen.Sessions()`, so a session opened from a host-filtered list comes
     * back to that same filter instead of silently clearing it, and one
     * opened from a task comes back to the task. Only a tab reselect or the
     * filter's own clear chip may drop a filter — not the unrelated act of
     * looking at a session and returning.
     */
    fun back(): Boolean {
        var handled = false
        move { s ->
            handled = isPushed(s.screen)
            if (!handled) {
                s
            } else {
                val to = s.history.lastOrNull() ?: rootOf(s.tab)
                s.copy(history = s.history.dropLast(1)).going(to)
            }
        }
        return handled
    }

    /**
     * Switch tabs. Leaves any open session behind rather than remembering it:
     * a session can be killed from the desktop while Settings is showing, and
     * "the app returns you to a session that no longer exists" is a worse
     * surprise than "the app returns you to the list". A host filter is the
     * same kind of state and leaves the same way — reselecting Sessions always
     * lands on the unfiltered list, never the one a host tap set up earlier.
     */
    fun select(tab: Tab) = move { it.copy(history = emptyList()).going(rootOf(tab)) }

    /**
     * Jump to the Sessions tab filtered to one host — what tapping a host row
     * on the Hosts screen does. Goes through [going] like every other move, so
     * the tab indicator follows it there without a separate `select` call.
     */
    fun showSessionsFor(alias: String) = move { it.copy(history = emptyList()).going(Screen.Sessions(hostAlias = alias)) }

    /**
     * Clear the current host filter — the Sessions bar's own clear-chip
     * action. A no-op unless the current screen actually is a Sessions
     * screen; there is nothing to clear from an open session, Hosts, or
     * Settings.
     *
     * This is [Screen] itself, not [SessionsViewModel]'s filter: the review
     * fix this closes found that the chip used to call `setHostFilter(null)`
     * directly on the view model, leaving [screen] still holding the old
     * `Screen.Sessions(alias)`. [open] reads the current screen to build
     * the history, so it captured the stale filter, and [back] restored it —
     * the filter came back the moment a session was opened and closed. With
     * one source of truth for the filter (this screen, not a second copy in
     * the view model), [open] can only ever capture what this actually set.
     */
    fun clearHostFilter() = move { if (it.screen is Screen.Sessions) it.going(Screen.Sessions()) else it }

    /**
     * The Work tab went away — the hub stopped serving `work { tree }`, or a
     * reconnect landed on an older hub. A Work screen has nothing to draw
     * then, so the app goes to the list rather than leaving a lit tab that
     * is no longer in the bar.
     */
    fun workUnavailable() = move { if (it.tab == Tab.Work) it.copy(history = emptyList()).going(rootOf(Tab.Sessions)) else it }

    /** The Files tab went away (a reconnect landed on a hub without downloads): as [workUnavailable]. */
    fun filesUnavailable() = move { if (it.tab == Tab.Files) it.copy(history = emptyList()).going(rootOf(Tab.Sessions)) else it }

    private fun NavState.going(screen: Screen): NavState = copy(
        screen = screen,
        // A session or the form belongs to the tab it was opened from — the
        // Work tab stays lit over a session opened from a task.
        tab = tabOf(screen) ?: history.lastOrNull()?.let(::tabOf) ?: tab,
    )

    /** One move: an atomic step of [nav], then [publish]. */
    private inline fun move(crossinline step: (NavState) -> NavState) {
        nav.update { step(it) }
        publish()
    }

    /**
     * Copy [nav] into [screen] and [tab]. Two threads publishing at once may
     * write in either order, so each checks afterwards that what it wrote is
     * still the latest and writes again if not: the last word is always the
     * newest state.
     */
    private fun publish() {
        while (true) {
            val s = nav.value
            _screen.value = s.screen
            _tab.value = s.tab
            if (nav.value == s) return
        }
    }

    private companion object {
        /** How many places back remembers; a longer walk forgets its oldest step. */
        const val MAX_HISTORY = 16
    }
}

/** A screen pushed over a tab, which back leaves; a tab's own screen is not one. */
internal fun isPushed(screen: Screen): Boolean =
    screen is Screen.Session || screen is Screen.NewSession || screen is Screen.Task

private fun rootOf(tab: Tab): Screen = when (tab) {
    Tab.Sessions -> Screen.Sessions()
    Tab.Work -> Screen.Work
    Tab.Files -> Screen.Files
    Tab.Hosts -> Screen.Hosts
    Tab.Settings -> Screen.Settings
}

/** The tab a screen lights, or null for one that belongs to whichever it was opened from. */
private fun tabOf(screen: Screen): Tab? = when (screen) {
    is Screen.Sessions -> Tab.Sessions
    is Screen.Session, is Screen.NewSession -> null
    Screen.Work, is Screen.Task -> Tab.Work
    Screen.Files -> Tab.Files
    Screen.Hosts -> Tab.Hosts
    Screen.Settings -> Tab.Settings
}
