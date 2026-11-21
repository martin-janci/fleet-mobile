package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Orbit Fleet bar (redesign 14.2): Inbox · Sessions · Control · Work ·
 * More, the only bar since the 14.13 sign-off, with every destination the old
 * Classic bar had still reachable. The parity half is
 * [every_former_tab_opens_from_the_bar]; the rest is how back and the lit tab
 * behave with Hosts, Files and Settings pushed over More.
 */
class NewLayoutTest {

    @Test
    fun the_bar_is_the_plans() {
        assertEquals(listOf(Tab.Inbox, Tab.Sessions, Tab.Control, Tab.Work, Tab.More), Tab.entries)
    }

    @Test
    fun the_app_opens_on_the_inbox() {
        assertEquals(Screen.Inbox, Navigator().screen.value)
        assertEquals(Tab.Inbox, Navigator().tab.value)
    }

    /** Nothing is lost, only moved: each screen the Classic bar had as a tab is reachable. */
    @Test
    fun every_former_tab_opens_from_the_bar() {
        val opens: List<Pair<Screen, (Navigator) -> Unit>> = listOf(
            Screen.Sessions() to { nav: Navigator -> nav.select(Tab.Sessions) },
            Screen.Work to { nav: Navigator -> nav.select(Tab.Work) },
            Screen.Files to { nav: Navigator -> nav.select(Tab.More); nav.openFromMore(Screen.Files) },
            Screen.Hosts to { nav: Navigator -> nav.select(Tab.More); nav.openFromMore(Screen.Hosts) },
            Screen.Settings to { nav: Navigator -> nav.select(Tab.More); nav.openFromMore(Screen.Settings) },
        )
        for ((expected, open) in opens) {
            val nav = Navigator()
            open(nav)
            assertEquals(expected, nav.screen.value, "$expected from the bar")
        }
    }

    @Test
    fun hosts_files_and_settings_sit_over_more_and_back_returns_there() {
        for (screen in listOf(Screen.Hosts, Screen.Files, Screen.Settings)) {
            val nav = Navigator()
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
        val nav = Navigator()
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
        val nav = Navigator()
        nav.open(7)
        assertEquals(Tab.Inbox, nav.tab.value)
        nav.back()
        assertEquals(Screen.Inbox, nav.screen.value)
    }

    @Test
    fun the_coordinator_session_opened_from_control_keeps_control_lit() {
        val nav = Navigator()
        nav.select(Tab.Control)
        nav.open(42)
        assertEquals(Tab.Control, nav.tab.value)
        nav.back()
        assertEquals(Screen.Control, nav.screen.value)
    }

    @Test
    fun losing_files_under_more_goes_back_to_more() {
        val nav = Navigator()
        nav.select(Tab.More)
        nav.openFromMore(Screen.Files)
        nav.filesUnavailable()
        assertEquals(Screen.More, nav.screen.value)
    }

    @Test
    fun losing_work_goes_to_the_inbox() {
        val nav = Navigator()
        nav.select(Tab.Work)
        nav.workUnavailable()
        assertEquals(Screen.Inbox, nav.screen.value)
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
        val down = HostRow(alias = "oci-arm", reachable = false, lastPingedAt = 100)
        val hidden = HostRow(alias = "old", reachable = false, hidden = true, lastPingedAt = 100)
        assertEquals("1 host", hostsLine(listOf(up)))
        assertEquals("2 hosts · oci-arm offline", hostsLine(listOf(up, down, hidden)))
        assertEquals("2 hosts · 2 offline", hostsLine(listOf(down, down.copy(alias = "nas"))))
        // Review w23-13: a host never checked yet is not offline.
        assertEquals("2 hosts", hostsLine(listOf(up, HostRow(alias = "new", reachable = false))))
    }
}
