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
}
