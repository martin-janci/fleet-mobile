package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Navigation is the one part of the five screens that is not Compose, and it is
 * here rather than inside a composable so that it can be asserted at all —
 * nothing in this repo can render a screen.
 */
class NavigatorTest {

    @Test
    fun the_app_opens_on_the_fleet_list() {
        val nav = Navigator()
        assertEquals(Screen.Sessions(), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    @Test
    fun opening_a_session_keeps_the_sessions_tab_lit() {
        val nav = Navigator()
        nav.open(7)

        assertEquals(Screen.Session(7), nav.screen.value)
        // A session belongs to the list it was opened from; lighting no tab at
        // all would make the bar look broken.
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    @Test
    fun back_from_a_session_returns_to_the_list() {
        val nav = Navigator()
        nav.open(7)

        assertTrue(nav.back(), "the app handled it")
        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /**
     * On a tab there is nowhere to go back to *inside* the app, and saying so
     * is what lets the Android host hand the gesture to the system instead of
     * swallowing it.
     */
    @Test
    fun back_on_a_tab_is_not_the_apps_to_handle() {
        val nav = Navigator()
        assertFalse(nav.back())
        assertEquals(Screen.Sessions(), nav.screen.value)

        nav.select(Tab.Hosts)
        assertFalse(nav.back())
        assertEquals(Screen.Hosts, nav.screen.value)
    }

    @Test
    fun switching_tabs_from_a_session_leaves_it() {
        val nav = Navigator()
        nav.open(7)
        nav.select(Tab.Settings)

        assertEquals(Screen.Settings, nav.screen.value)
        assertEquals(Tab.Settings, nav.tab.value)

        // And coming back shows the list, not the session that was open. A
        // remembered session is a rule with an expiry date on it — the session
        // can be killed from the desktop while the Settings tab is showing.
        nav.select(Tab.Sessions)
        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    @Test
    fun selecting_the_tab_already_showing_changes_nothing() {
        val nav = Navigator()
        nav.select(Tab.Sessions)
        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /**
     * Pairing is not a tab and not reachable from one: the app is on the Pair
     * screen exactly when it holds no credential, which is `AuthState`'s
     * business. Coming back from Pair therefore has to start from the top.
     */
    @Test
    fun a_fresh_navigator_after_pairing_starts_at_the_list() {
        val nav = Navigator()
        nav.select(Tab.Settings)

        assertEquals(Screen.Sessions(), Navigator().screen.value)
        assertEquals(Screen.Settings, nav.screen.value)
    }

    /** Tapping a host row jumps straight to Sessions, filtered to that host. */
    @Test
    fun tapping_a_host_shows_sessions_filtered_to_it() {
        val nav = Navigator()
        nav.select(Tab.Hosts)

        nav.showSessionsFor("mefistos")

        assertEquals(Screen.Sessions(hostAlias = "mefistos"), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    /**
     * Reselecting the Sessions tab clears the host filter, the same
     * "leave state behind" rule [select] already applies to an open session.
     */
    @Test
    fun reselecting_the_sessions_tab_clears_the_host_filter() {
        val nav = Navigator()
        nav.showSessionsFor("mefistos")

        nav.select(Tab.Sessions)

        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /**
     * Review fix round 1: `back()` used to build a bare `Screen.Sessions()`,
     * so opening a session from a host-filtered list and coming back dropped
     * the filter. `open()` now remembers the Sessions screen it was called
     * from, filter and all, and `back()` restores exactly that.
     */
    @Test
    fun back_from_a_session_opened_from_a_filtered_list_keeps_the_filter() {
        val nav = Navigator()
        nav.showSessionsFor("mefistos")

        nav.open(7)
        assertTrue(nav.back())

        assertEquals(Screen.Sessions(hostAlias = "mefistos"), nav.screen.value)
    }

    /** The unfiltered case still works the same as before this fix. */
    @Test
    fun back_from_a_session_opened_from_the_unfiltered_list_stays_unfiltered() {
        val nav = Navigator()
        nav.open(7)

        assertTrue(nav.back())

        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /**
     * A restored filter is still just a [select]'d Sessions screen underneath
     * — reselecting the tab clears it exactly as it would if the filter had
     * come from a host tap moments before.
     */
    @Test
    fun reselecting_after_a_filtered_back_still_clears_the_filter() {
        val nav = Navigator()
        nav.showSessionsFor("mefistos")
        nav.open(7)
        nav.back()

        nav.select(Tab.Sessions)

        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /**
     * Final review fix wave, I1: the chip used to clear the filter by calling
     * `setHostFilter(null)` straight on the view model, leaving
     * `Navigator.screen` still holding `Screen.Sessions("mefistos")`. `open()`
     * captures `returnTo` from `screen.value`, so it captured the stale
     * filter, and `back()` restored it — clearing the chip and then visiting
     * a session brought the filter right back. `clearHostFilter()` fixes
     * this by being the one thing that changes `screen`, so there is nothing
     * stale left for `open()` to capture.
     */
    @Test
    fun clearing_the_filter_then_opening_a_session_and_coming_back_stays_cleared() {
        val nav = Navigator()
        nav.showSessionsFor("mefistos")

        nav.clearHostFilter()
        nav.open(1)
        assertTrue(nav.back())

        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    /** Nothing to clear from a session, Hosts, or Settings — the chip only exists on Sessions. */
    @Test
    fun clearHostFilter_on_a_non_sessions_screen_is_a_no_op() {
        val nav = Navigator()
        nav.select(Tab.Hosts)

        nav.clearHostFilter()

        assertEquals(Screen.Hosts, nav.screen.value)
    }

    /**
     * The form is pushed over the list like a session is, so the Sessions tab
     * stays lit, and a host filter on the list becomes the form's first guess
     * at a host — the person was already looking at that machine.
     */
    @Test
    fun new_session_is_pushed_over_the_list_and_takes_its_host_filter() {
        val nav = Navigator()
        nav.showSessionsFor("pine")

        nav.newSession()

        assertEquals(Screen.NewSession(hostAlias = "pine"), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    @Test
    fun back_from_the_form_returns_to_the_list_as_it_stood() {
        val nav = Navigator()
        nav.showSessionsFor("pine")
        nav.newSession()

        assertTrue(nav.back(), "the app handled it")
        assertEquals(Screen.Sessions(hostAlias = "pine"), nav.screen.value)
    }

    /**
     * Once the session exists the form has done its job. Back from the new
     * session goes to the list, not to a form that would create a second one.
     */
    @Test
    fun back_from_the_session_the_form_created_skips_the_form() {
        val nav = Navigator()
        nav.showSessionsFor("pine")
        nav.newSession()

        nav.created(41)
        assertEquals(Screen.Session(41), nav.screen.value)

        assertTrue(nav.back())
        assertEquals(Screen.Sessions(hostAlias = "pine"), nav.screen.value)
    }

    /** Only the list offers the form; anywhere else there is no list to return to. */
    @Test
    fun new_session_from_another_tab_does_nothing() {
        val nav = Navigator()
        nav.select(Tab.Hosts)

        nav.newSession()

        assertEquals(Screen.Hosts, nav.screen.value)
    }

    /** A create that finishes after the person left the form does not pull them back. */
    @Test
    fun a_create_finishing_after_the_form_was_left_changes_nothing() {
        val nav = Navigator()
        nav.newSession()
        nav.back()

        nav.created(41)
        assertEquals(Screen.Sessions(), nav.screen.value)

        nav.newSession()
        nav.select(Tab.Settings)
        nav.created(42)
        assertEquals(Screen.Settings, nav.screen.value)
    }

    /** Start here from the Tickets sheet: the same form in ticket mode, and the made session opens like any other. */
    @Test
    fun start_here_opens_the_form_in_ticket_mode_and_created_opens_the_session() {
        val nav = Navigator()
        nav.showSessionsFor("pine")
        nav.newSession(ticketKey = "PAY-9")

        assertEquals(Screen.NewSession(hostAlias = "pine", ticketKey = "PAY-9"), nav.screen.value)
        nav.created(41)
        assertEquals(Screen.Session(41), nav.screen.value)
        nav.back()
        assertEquals(Screen.Sessions("pine"), nav.screen.value, "back lands on the list, never on a form that would start it again")
    }

    // ---- the Work view (claude-fleet M14) ----

    @Test
    fun the_work_tab_is_a_root_like_the_others() {
        val nav = Navigator()
        nav.select(Tab.Work)

        assertEquals(Screen.Work, nav.screen.value)
        assertEquals(Tab.Work, nav.tab.value)
        assertFalse(nav.back(), "a tab: the platform's to handle")
    }

    /** Task → session → back lands on the task, and back again on the Work view — with the Work tab lit throughout. */
    @Test
    fun task_then_session_then_back_returns_to_the_task_then_to_work() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.openTask("item:12")
        assertEquals(Screen.Task("item:12"), nav.screen.value)

        nav.open(7)
        assertEquals(Tab.Work, nav.tab.value, "a session opened from a task keeps the Work tab lit")

        assertTrue(nav.back())
        assertEquals(Screen.Task("item:12"), nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Work, nav.screen.value)
        assertFalse(nav.back())
    }

    /** A task opened from a session's Tasks comes back to that session, then to the list it was opened from. */
    @Test
    fun a_task_opened_from_a_session_returns_to_the_session() {
        val nav = Navigator()
        nav.showSessionsFor("pine")
        nav.open(7)
        nav.openTask("ref:OPS-1")

        nav.open(9)
        assertTrue(nav.back())
        assertEquals(Screen.Task("ref:OPS-1"), nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Session(7), nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Sessions("pine"), nav.screen.value)
    }

    /** Start here from a task: the form in ticket mode, and back from the made session lands on the task, never the form. */
    @Test
    fun start_here_from_a_task_returns_to_the_task() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.openTask("item:12")

        nav.newSession(ticketKey = "ABC-12")
        assertEquals(Screen.NewSession(hostAlias = null, ticketKey = "ABC-12"), nav.screen.value)
        assertEquals(Tab.Work, nav.tab.value)
        nav.created(41)
        assertEquals(Screen.Session(41), nav.screen.value)

        assertTrue(nav.back())
        assertEquals(Screen.Task("item:12"), nav.screen.value)
    }

    /** A plain New session is the list's; a task offers only ticket mode. */
    @Test
    fun a_plain_new_session_from_a_task_does_nothing() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.openTask("item:12")

        nav.newSession()

        assertEquals(Screen.Task("item:12"), nav.screen.value)
    }

    @Test
    fun switching_tabs_forgets_the_way_back() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.openTask("item:12")
        nav.open(7)

        nav.select(Tab.Sessions)
        nav.open(8)
        assertTrue(nav.back())

        assertEquals(Screen.Sessions(), nav.screen.value, "not the task from the other tab")
    }

    /** The hub stopped serving the Work view: a lit Work tab goes back to the list; any other tab stays. */
    @Test
    fun losing_the_work_view_leaves_the_work_tab() {
        val nav = Navigator()
        nav.select(Tab.Hosts)
        nav.workUnavailable()
        assertEquals(Screen.Hosts, nav.screen.value)

        nav.select(Tab.Work)
        nav.openTask("item:12")
        nav.workUnavailable()
        assertEquals(Screen.Sessions(), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    @Test
    fun opening_the_task_already_showing_adds_no_step() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.openTask("item:12")
        nav.openTask("item:12")

        assertTrue(nav.back())
        assertEquals(Screen.Work, nav.screen.value)
    }

    @Test
    fun a_worktree_opens_over_its_session_and_back_returns_to_it() {
        val nav = Navigator()
        nav.open(7)
        nav.openRepo(7)

        assertEquals(Screen.Repo(7), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)

        assertTrue(nav.back())
        assertEquals(Screen.Session(7), nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Sessions(), nav.screen.value)
    }

    @Test
    fun opening_the_same_worktree_twice_is_one_step() {
        val nav = Navigator()
        nav.open(7)
        nav.openRepo(7)
        nav.openRepo(7)

        assertTrue(nav.back())
        assertEquals(Screen.Session(7), nav.screen.value)
    }

    @Test
    fun usage_opens_over_settings_and_back_returns_there() {
        val nav = Navigator()
        nav.select(Tab.Settings)
        nav.openUsage()

        assertEquals(Screen.Usage, nav.screen.value)
        assertEquals(Tab.Settings, nav.tab.value)
        assertTrue(nav.back())
        assertEquals(Screen.Settings, nav.screen.value)
    }

    @Test
    fun a_new_session_in_a_project_opens_the_form_with_it_picked() {
        val nav = Navigator()
        nav.newSessionIn(3)
        assertEquals(Screen.NewSession(projectId = 3), nav.screen.value)
        assertTrue(nav.back())
        assertEquals(Screen.Sessions(), nav.screen.value)
    }
}
