package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The New bar (redesign 14.2): Inbox · Sessions · Control · Work · More, with
 * every Classic destination still reachable. The parity half of the step is
 * [every_classic_destination_opens_from_new]; the rest is how back and the
 * lit tab behave once Hosts, Files and Settings are pushed over More.
 */
class NewLayoutTest {

    @Test
    fun the_bars_are_the_plans() {
        assertEquals(listOf(Tab.Sessions, Tab.Work, Tab.Files, Tab.Hosts, Tab.Settings), PhoneLayout.Classic.tabs)
        assertEquals(listOf(Tab.Inbox, Tab.Sessions, Tab.Control, Tab.Work, Tab.More), PhoneLayout.New.tabs)
    }

    @Test
    fun new_opens_on_the_inbox_and_classic_on_the_list() {
        assertEquals(Screen.Inbox, Navigator(PhoneLayout.New).screen.value)
        assertEquals(Tab.Inbox, Navigator(PhoneLayout.New).tab.value)
        assertEquals(Screen.Sessions(), Navigator().screen.value)
    }

    /** Nothing is lost, only moved: each Classic tab's screen is reachable on the New bar. */
    @Test
    fun every_classic_destination_opens_from_new() {
        for (tab in PhoneLayout.Classic.tabs) {
            val nav = Navigator(PhoneLayout.New)
            when (tab) {
                Tab.Sessions -> nav.select(Tab.Sessions)
                Tab.Work -> nav.select(Tab.Work)
                Tab.Files -> { nav.select(Tab.More); nav.openFromMore(Screen.Files) }
                Tab.Hosts -> { nav.select(Tab.More); nav.openFromMore(Screen.Hosts) }
                Tab.Settings -> { nav.select(Tab.More); nav.openFromMore(Screen.Settings) }
                else -> error("not a Classic tab: $tab")
            }
            val expected = when (tab) {
                Tab.Sessions -> Screen.Sessions()
                Tab.Work -> Screen.Work
                Tab.Files -> Screen.Files
                Tab.Hosts -> Screen.Hosts
                else -> Screen.Settings
            }
            assertEquals(expected, nav.screen.value, "$tab from the New bar")
        }
    }

    @Test
    fun hosts_files_and_settings_sit_over_more_and_back_returns_there() {
        for (screen in listOf(Screen.Hosts, Screen.Files, Screen.Settings)) {
            val nav = Navigator(PhoneLayout.New)
            nav.select(Tab.More)
            nav.openFromMore(screen)
            assertEquals(Tab.More, nav.tab.value, "More stays lit over $screen")
            assertTrue(nav.isPushed(screen), "back is the app's on $screen")
            assertTrue(nav.back())
            assertEquals(Screen.More, nav.screen.value)
            assertFalse(nav.back(), "More is a tab: back goes to the system")
        }
    }

    @Test
    fun usage_and_company_open_over_more_too() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.More)
        nav.openUsage()
        assertEquals(Tab.More, nav.tab.value)
        nav.back()
        nav.openCompany()
        assertEquals(Screen.Company, nav.screen.value)
        nav.back()
        assertEquals(Screen.More, nav.screen.value)
    }

    @Test
    fun a_session_opened_from_the_inbox_comes_back_to_it() {
        val nav = Navigator(PhoneLayout.New)
        nav.open(7)
        assertEquals(Tab.Inbox, nav.tab.value)
        nav.back()
        assertEquals(Screen.Inbox, nav.screen.value)
    }

    @Test
    fun the_coordinator_session_opened_from_control_keeps_control_lit() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.Control)
        nav.open(42)
        assertEquals(Tab.Control, nav.tab.value)
        nav.back()
        assertEquals(Screen.Control, nav.screen.value)
    }

    @Test
    fun on_classic_hosts_files_and_settings_stay_tabs() {
        val nav = Navigator()
        nav.openFromMore(Screen.Hosts)
        assertEquals(Tab.Hosts, nav.tab.value)
        assertFalse(nav.isPushed(Screen.Hosts))
        assertFalse(nav.back())
    }

    @Test
    fun switching_layout_starts_on_the_new_bars_first_tab() {
        val nav = Navigator()
        nav.open(7)
        nav.setLayout(PhoneLayout.New)
        assertEquals(Screen.Inbox, nav.screen.value)
        assertEquals(PhoneLayout.New, nav.layout.value)
        assertFalse(nav.back(), "no history carried across")
        nav.setLayout(PhoneLayout.Classic)
        assertEquals(Screen.Sessions(), nav.screen.value)
        assertEquals(Tab.Sessions, nav.tab.value)
    }

    @Test
    fun losing_files_under_more_goes_back_to_more() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.More)
        nav.openFromMore(Screen.Files)
        nav.filesUnavailable()
        assertEquals(Screen.More, nav.screen.value)
    }

    @Test
    fun losing_work_on_new_goes_to_the_inbox() {
        val nav = Navigator(PhoneLayout.New)
        nav.select(Tab.Work)
        nav.workUnavailable()
        assertEquals(Screen.Inbox, nav.screen.value)
    }

    @Test
    fun the_layout_is_remembered_and_classic_is_the_default() {
        val prefs = FakePrefs()
        assertEquals(PhoneLayout.Classic, loadPhoneLayout(prefs))
        savePhoneLayout(prefs, PhoneLayout.New)
        assertEquals(PhoneLayout.New, loadPhoneLayout(prefs))
        savePhoneLayout(prefs, PhoneLayout.Classic)
        assertEquals(PhoneLayout.Classic, loadPhoneLayout(prefs))
        prefs.putStringList(LAYOUT_PREF, listOf("something-newer"))
        assertEquals(PhoneLayout.Classic, loadPhoneLayout(prefs), "an unknown value falls back to Classic")
    }

    @Test
    fun the_inbox_is_every_session_that_needs_you_oldest_first() {
        val rows = listOf(
            SessionRow(id = 1, tmuxName = "a", claudeStatus = "working", lastActivityAt = 10),
            SessionRow(id = 2, tmuxName = "b", claudeStatus = "blocked", lastActivityAt = 300),
            SessionRow(id = 3, tmuxName = "c", claudeStatus = "failed", lastActivityAt = 100),
            SessionRow(id = 4, tmuxName = "d", claudeStatus = "blocked", lastActivityAt = null),
        )
        assertEquals(listOf(3L, 2L, 4L), inboxRows(rows).map { it.id })
        assertEquals(StatusWord.FAILED, inboxWord(rows[2]))
        assertEquals(StatusWord.NEEDS_YOU, inboxWord(rows[1]))
    }

    @Test
    fun the_hosts_row_says_what_is_offline() {
        val up = HostRow(alias = "mac", reachable = true)
        val down = HostRow(alias = "oci-arm", reachable = false)
        val hidden = HostRow(alias = "old", reachable = false, hidden = true)
        assertEquals("1 host", hostsLine(listOf(up)))
        assertEquals("2 hosts · oci-arm offline", hostsLine(listOf(up, down, hidden)))
        assertEquals("2 hosts · 2 offline", hostsLine(listOf(down, down.copy(alias = "nas"))))
    }
}
