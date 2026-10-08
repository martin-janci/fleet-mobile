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
    data class Sessions(
        val hostAlias: String? = null,
        /**
         * The sheet to put back up on arriving here: set only on the history
         * entry [Navigator.openFrom] leaves, so back from a session opened out
         * of Today, Tidy-up or Tickets lands in that sheet, not on the bare list.
         */
        val reopen: SessionsSheet? = null,
    ) : Screen
    data class Session(val id: Long) : Screen

    /**
     * The New session form, pushed over the list it was opened from.
     * [hostAlias] is that list's host filter — the form's first guess at a host.
     */
    data class NewSession(
        val hostAlias: String? = null,
        /** Ticket mode (M8.4): start work on this key rather than a plain session. */
        val ticketKey: String? = null,
        /** The project to start in, chosen already (a search hit). */
        val projectId: Long? = null,
    ) : Screen
    data object Hosts : Screen
    data object Settings : Screen

    /** The Work view (claude-fleet M14): org → group → task. A tab of its own. */
    data object Work : Screen

    /** One task and all of its sessions, pushed over the Work view (or a session's Tasks). */
    data class Task(val taskId: String) : Screen

    /** The hub's copies of files sessions sent (claude-fleet file downloads). A tab of its own. */
    data object Files : Screen

    /** One session's worktree — changes, history, files — pushed over that session. */
    data class Repo(val sessionId: Long) : Screen

    /** The fleet's estimated usage and its Claude accounts, pushed over Settings. */
    data object Usage : Screen

    /** The company's organisations, read-only (claude-fleet's company administration), pushed over Settings. */
    data object Company : Screen

    /** New layout only (redesign 14.2): what needs a person, oldest ask first. The first tab. */
    data object Inbox : Screen

    /** New layout only: the coordinator (the fleet agent) and missions. Replaces the agent button. */
    data object Control : Screen

    /** New layout only: Hosts, accounts and usage, automation, Files, organisations and Settings. */
    data object More : Screen

    /** The phone app's own update (redesign 14.18): download, signature, install. Pushed over More, Inbox or Settings. */
    data object Update : Screen

    /** More › Learn (redesign 14.22): the practice fleet, lessons and guides. */
    data object Learn : Screen

    /** The practice fleet: sample sessions on this phone that never reach the hub. */
    data object Practice : Screen

    /** One of the hub's approved guides, step by step, with Undo per change. */
    data class Guide(val pageId: String) : Screen
}

/**
 * Which bottom bar the phone draws (redesign 14.2), like the desktop's
 * `ui.layout`. [Classic] is today's five tabs; [New] is the Orbit Fleet bar,
 * Inbox · Sessions · Control · Work · More, where Files, Hosts and Settings
 * open from More instead of being tabs. Nothing is removed, only moved.
 */
enum class PhoneLayout(val tabs: List<Tab>) {
    Classic(listOf(Tab.Sessions, Tab.Work, Tab.Files, Tab.Hosts, Tab.Settings)),
    New(listOf(Tab.Inbox, Tab.Sessions, Tab.Control, Tab.Work, Tab.More)),
}

/**
 * The destinations in the bottom bar; which ones depends on [PhoneLayout].
 * [Work] is drawn only when the hub serves the Work view (`work { tree }`),
 * and [Files] only when it keeps downloads (`list_downloads`) — see `App.kt`.
 */
enum class Tab { Sessions, Work, Files, Hosts, Settings, Inbox, Control, More }

/** The Sessions list's sheets a session can be opened from, and back returns to. */
enum class SessionsSheet { Today, Tidy, Tickets }

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
class Navigator(layout: PhoneLayout = PhoneLayout.Classic) {
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
        val layout: PhoneLayout = PhoneLayout.Classic,
        val screen: Screen = rootOf(layout.tabs.first()),
        val tab: Tab = layout.tabs.first(),
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

    private val nav = MutableStateFlow(NavState(layout = layout))

    private val _screen = MutableStateFlow(nav.value.screen)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _tab = MutableStateFlow(nav.value.tab)

    private val _layout = MutableStateFlow(layout)

    /** The bar being drawn. */
    val layout: StateFlow<PhoneLayout> = _layout.asStateFlow()

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
     * Open a session from one of the list's sheets: back comes to the list
     * with [sheet] put back up. Anywhere but the list it is a plain [open].
     */
    fun openFrom(sessionId: Long, sheet: SessionsSheet) = move { s ->
        val from = (s.screen as? Screen.Sessions)?.copy(reopen = sheet) ?: s.screen
        s.pushing(from).going(Screen.Session(sessionId))
    }

    /** The list put its sheet back up: forget it, so a later visit does not reopen it again. */
    fun sheetReopened() = move { s ->
        val current = s.screen
        if (current is Screen.Sessions && current.reopen != null) s.copy(screen = current.copy(reopen = null)) else s
    }

    /** Open the Usage screen over whatever is showing (Settings); back returns there. */
    fun openUsage() = move { s -> if (s.screen == Screen.Usage) s else s.pushing(s.screen).going(Screen.Usage) }

    /** Open the Updating screen over whatever is showing (More, Inbox, Settings); back returns there. */
    fun openUpdate() = move { s -> if (s.screen == Screen.Update) s else s.pushing(s.screen).going(Screen.Update) }

    /** Open Learn over whatever is showing (More); back returns there. */
    fun openLearn() = move { s -> if (s.screen == Screen.Learn) s else s.pushing(s.screen).going(Screen.Learn) }

    /** Open the practice fleet over whatever is showing (Learn, the help picker, Settings). */
    fun openPractice() = move { s -> if (s.screen == Screen.Practice) s else s.pushing(s.screen).going(Screen.Practice) }

    /** Open one guide over Learn. */
    fun openGuide(pageId: String) = move { s ->
        if (s.screen == Screen.Guide(pageId)) s else s.pushing(s.screen).going(Screen.Guide(pageId))
    }

    /** Open the Company screen over whatever is showing (Settings); back returns there. */
    fun openCompany() = move { s -> if (s.screen == Screen.Company) s else s.pushing(s.screen).going(Screen.Company) }

    /** Open a session's worktree (changes, history, files); back returns to the session. */
    fun openRepo(sessionId: Long) = move { s ->
        if (s.screen == Screen.Repo(sessionId)) s else s.pushing(s.screen, sessionIsAPlace = true).going(Screen.Repo(sessionId))
    }

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
    /** The New session form with [projectId] picked already — a search hit's "New session in…". */
    fun newSessionIn(projectId: Long) = move { s -> s.pushing(s.screen).going(Screen.NewSession(projectId = projectId)) }

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
            handled = isPushedOn(s.screen, s.layout)
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
     * Switch between the Classic and New bars. Starts over on the new bar's
     * first tab, as a tab switch does: a screen that was a tab in one layout
     * (Hosts) is pushed in the other, so carrying the history across would
     * leave back pointing at places the new bar does not have.
     */
    fun setLayout(layout: PhoneLayout) = move { s ->
        if (s.layout == layout) s else NavState(layout = layout)
    }

    /**
     * Open one of More's destinations over More (New layout): Hosts, Files or
     * Settings, which are tabs only on the Classic bar. Back returns to More.
     * On the Classic bar it is the tab itself.
     */
    fun openFromMore(screen: Screen) = move { s ->
        when {
            s.layout == PhoneLayout.Classic -> tabOf(screen, s.layout)?.let { s.copy(history = emptyList()).going(rootOf(it)) } ?: s
            s.screen == screen -> s
            else -> s.pushing(s.screen).going(screen)
        }
    }

    /** Whether back is the app's to handle on [screen] under the current layout. See [isPushedOn]. */
    fun isPushed(screen: Screen): Boolean = isPushedOn(screen, nav.value.layout)

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
    fun workUnavailable() = move {
        if (it.tab == Tab.Work) it.copy(history = emptyList()).going(rootOf(it.layout.tabs.first())) else it
    }

    /**
     * The Files tab went away (a reconnect landed on a hub without downloads):
     * as [workUnavailable]. On the New bar Files is under More, so the app
     * goes back to More.
     */
    fun filesUnavailable() = move {
        when {
            it.tab == Tab.Files -> it.copy(history = emptyList()).going(rootOf(Tab.Sessions))
            it.screen == Screen.Files -> it.copy(history = emptyList()).going(Screen.More)
            else -> it
        }
    }

    private fun NavState.going(screen: Screen): NavState = copy(
        screen = screen,
        // A session or the form belongs to the tab it was opened from — the
        // Work tab stays lit over a session opened from a task.
        tab = tabOf(screen, layout) ?: history.lastOrNull()?.let { tabOf(it, layout) } ?: tab,
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
            _layout.value = s.layout
            if (nav.value == s) return
        }
    }

    private companion object {
        /** How many places back remembers; a longer walk forgets its oldest step. */
        const val MAX_HISTORY = 16
    }
}

/**
 * A screen pushed over a tab, which back leaves; a tab's own screen is not
 * one. Hosts, Files and Settings are tabs on the Classic bar and pushed over
 * More on the New one.
 */
internal fun isPushedOn(screen: Screen, layout: PhoneLayout): Boolean =
    screen is Screen.Session || screen is Screen.NewSession || screen is Screen.Task || screen is Screen.Repo ||
        screen == Screen.Usage || screen == Screen.Company || screen == Screen.Update ||
        screen == Screen.Learn || screen == Screen.Practice || screen is Screen.Guide ||
        (layout == PhoneLayout.New && (screen == Screen.Hosts || screen == Screen.Files || screen == Screen.Settings))

private fun rootOf(tab: Tab): Screen = when (tab) {
    Tab.Sessions -> Screen.Sessions()
    Tab.Work -> Screen.Work
    Tab.Files -> Screen.Files
    Tab.Hosts -> Screen.Hosts
    Tab.Settings -> Screen.Settings
    Tab.Inbox -> Screen.Inbox
    Tab.Control -> Screen.Control
    Tab.More -> Screen.More
}

/** The tab a screen lights, or null for one that belongs to whichever it was opened from. */
private fun tabOf(screen: Screen, layout: PhoneLayout): Tab? = when (screen) {
    is Screen.Sessions -> Tab.Sessions
    is Screen.Session, is Screen.NewSession, is Screen.Repo, Screen.Usage, Screen.Company, Screen.Update,
    Screen.Learn, Screen.Practice, is Screen.Guide -> null
    Screen.Work, is Screen.Task -> Tab.Work
    Screen.Files -> if (layout == PhoneLayout.New) null else Tab.Files
    Screen.Hosts -> if (layout == PhoneLayout.New) null else Tab.Hosts
    Screen.Settings -> if (layout == PhoneLayout.New) null else Tab.Settings
    Screen.Inbox -> Tab.Inbox
    Screen.Control -> Tab.Control
    Screen.More -> Tab.More
}
