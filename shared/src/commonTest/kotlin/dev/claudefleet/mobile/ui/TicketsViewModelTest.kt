@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.LiveWork
import dev.claudefleet.mobile.model.ResumeMode
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PAY9 = Ticket(id = 90, key = "PAY-9", title = "Ledger")
private val PAY7 = Ticket(id = 70, key = "PAY-7", title = "Refund", liveSessionIds = listOf(5))

/** A live session with no work of its own: what makes a listed or planned id count as live. */
private fun alive(id: Long) = SessionRow(id = id, tmuxName = "s$id", hostAlias = "pine")

private fun plan(ok: Boolean = true, live: List<LiveWork> = emptyList()) = ResumePlan(
    key = "PAY-9",
    hostAlias = "pine",
    live = live,
    modes = listOf(ResumeMode("last", ok = ok), ResumeMode("fresh", ok = true)),
    hosts = listOf("pine", "hetzner"),
)

class TicketsViewModelTest {

    private class Nav {
        val opened = mutableListOf<Long>()
        val started = mutableListOf<String>()
    }

    private fun vm(
        fleet: WorkFleet,
        actions: FakeWorkActions,
        scope: kotlinx.coroutines.CoroutineScope,
        nav: Nav,
        canWrite: Boolean = true,
    ) = TicketsViewModel(fleet, actions, scope, canWrite, onOpenSession = { nav.opened += it }, onStartHere = { nav.started += it })

    @Test
    fun opening_the_sheet_reads_my_work_the_sprint_and_recent() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply { ticketsAnswer = mapOf("mine" to listOf(PAY9), "recent" to listOf(PAY7)) }
        val tickets = vm(fleet, actions, backgroundScope, Nav())

        assertTrue(tickets.state.value.available)
        tickets.open()
        runCurrent()

        assertEquals(listOf("My work", "Current sprint", "Recent"), tickets.state.value.sections.map { it.title })
        assertEquals(listOf("PAY-9"), tickets.state.value.sections[0].tickets.map { it.key })
        assertEquals(setOf("tickets mine", "tickets sprint", "tickets recent"), actions.calls.toSet())
        assertEquals(listOf(90L, 70L), fleet.remembered.map { it.id }, "the cache learns what the sheet listed")
    }

    @Test
    fun a_hub_without_the_work_graph_offers_no_sheet() = runTest {
        val actions = FakeWorkActions()
        val tickets = vm(WorkFleet(caps = HubCapabilities()), actions, backgroundScope, Nav())

        assertFalse(tickets.state.value.available)
        tickets.open()
        runCurrent()
        assertEquals(emptyList(), actions.calls)
    }

    /** A ticket a session is already on offers Open, and never Start. */
    @Test
    fun a_ticket_with_a_live_session_offers_open_and_open_jumps_there() = runTest {
        val nav = Nav()
        val tickets = vm(WorkFleet(listOf(alive(5))), FakeWorkActions(), backgroundScope, nav)
        tickets.select(PAY7)
        runCurrent()

        val detail = tickets.state.value.selected!!
        assertEquals(5L, detail.liveSessionId)
        assertFalse(detail.canStart)
        tickets.openLive()
        assertEquals(listOf(5L), nav.opened)
    }

    /** The fleet's own rows count as live too: a session linked since the listing still means Jump. */
    @Test
    fun a_session_linked_since_the_listing_also_means_open() = runTest {
        val linked = SessionRow(id = 8, tmuxName = "t", work = WorkSummary(linkId = 1, itemId = 90, key = "PAY-9"))
        val tickets = vm(WorkFleet(rows = listOf(linked)), FakeWorkActions(), backgroundScope, Nav())
        tickets.select(PAY9)
        runCurrent()

        assertEquals(8L, tickets.state.value.selected?.liveSessionId)
        assertFalse(tickets.state.value.selected!!.canStart)
    }

    @Test
    fun start_here_hands_the_key_to_the_form() = runTest {
        val nav = Nav()
        val tickets = vm(WorkFleet(), FakeWorkActions(), backgroundScope, nav)
        tickets.select(PAY9)
        runCurrent()

        assertTrue(tickets.state.value.selected!!.canStart)
        tickets.startHere()
        assertEquals(listOf("PAY-9"), nav.started)
    }

    /** Resume uses the `last` mode, with only a host to choose — the override is passed through. */
    @Test
    fun resume_uses_the_last_mode_and_passes_the_host_override() = runTest {
        val nav = Nav()
        val actions = FakeWorkActions().apply { planAnswer = plan() }
        val tickets = vm(WorkFleet(), actions, backgroundScope, nav)
        tickets.select(PAY9)
        runCurrent()

        val detail = tickets.state.value.selected!!
        assertTrue(detail.canResume)
        assertEquals(listOf("pine", "hetzner"), detail.resumeHosts)
        assertEquals("pine", detail.resumeHost, "the hub's own suggestion is the default")
        tickets.selectResumeHost("hetzner")
        tickets.resume()
        runCurrent()

        assertEquals(listOf("resume_plan PAY-9", "resume PAY-9 hetzner"), actions.calls)
        assertEquals(listOf(99L), nav.opened)
    }

    @Test
    fun a_plan_that_cannot_resume_last_offers_no_resume() = runTest {
        val actions = FakeWorkActions().apply { planAnswer = plan(ok = false) }
        val tickets = vm(WorkFleet(), actions, backgroundScope, Nav())
        tickets.select(PAY9)
        runCurrent()

        assertFalse(tickets.state.value.selected!!.canResume)
        assertTrue(tickets.state.value.selected!!.canStart)
    }

    /** The plan saying it is live is Jump, not Resume and not Start. */
    @Test
    fun a_plan_that_says_live_is_a_jump() = runTest {
        val actions = FakeWorkActions().apply { planAnswer = plan(ok = false, live = listOf(LiveWork(sessionId = 12, hostAlias = "pine"))) }
        val tickets = vm(WorkFleet(listOf(alive(12))), actions, backgroundScope, Nav())
        tickets.select(PAY9)
        runCurrent()

        val detail = tickets.state.value.selected!!
        assertEquals(12L, detail.liveSessionId)
        assertFalse(detail.canStart || detail.canResume)
    }

    @Test
    fun a_resume_refused_as_existing_jumps_to_the_named_session() = runTest {
        val nav = Nav()
        val actions = FakeWorkActions().apply {
            planAnswer = plan()
            fail = HubError.Tool("E_EXISTS", "PAY-9 is live; jump to it", buildJsonObject { put("session_id", 41) })
        }
        val tickets = vm(WorkFleet(), actions, backgroundScope, nav)
        tickets.select(PAY9)
        runCurrent()
        tickets.resume()
        runCurrent()

        assertEquals(listOf(41L), nav.opened)
        assertNull(tickets.state.value.error)
    }

    @Test
    fun a_readonly_token_gets_neither_start_nor_resume() = runTest {
        val actions = FakeWorkActions().apply { planAnswer = plan() }
        val nav = Nav()
        val tickets = vm(WorkFleet(), actions, backgroundScope, nav, canWrite = false)
        tickets.select(PAY9)
        runCurrent()

        assertFalse(tickets.state.value.selected!!.canStart || tickets.state.value.selected!!.canResume)
        tickets.startHere(); tickets.resume()
        runCurrent()
        assertEquals(emptyList(), nav.started)
        assertEquals(listOf("resume_plan PAY-9"), actions.calls, "reading the plan is fine; nothing was written")
    }

    @Test
    fun search_looks_up_a_key_or_url_and_selects_what_it_found() = runTest {
        val actions = FakeWorkActions().apply { lookupAnswer = PAY9 }
        val tickets = vm(WorkFleet(caps = HubCapabilities.of(ToolCatalog(setOf("work")))), actions, backgroundScope, Nav())
        tickets.onQuery(" https://acme.atlassian.net/browse/PAY-9 ")
        tickets.search()
        runCurrent()

        assertEquals("PAY-9", tickets.state.value.found?.key)
        assertEquals(90L, tickets.state.value.selected?.ticket?.id)
        assertFalse(tickets.state.value.selected!!.canStart, "a hub that hides work_link: read-only")
        assertEquals("lookup https://acme.atlassian.net/browse/PAY-9", actions.calls.first())
    }

    /**
     * The listing's `live_session_ids` and the plan's `live` are snapshots
     * from when the sheet read them. A session killed while the sheet is open
     * leaves the fleet, and the ticket must stop offering Open on it.
     */
    @Test
    fun a_session_killed_with_the_sheet_open_is_not_offered_as_open() = runTest {
        val fleet = WorkFleet(listOf(alive(5)))
        val tickets = vm(fleet, FakeWorkActions(), backgroundScope, Nav())
        tickets.select(PAY7)
        runCurrent()
        assertEquals(5L, tickets.state.value.selected!!.liveSessionId)

        fleet.sessions.value = emptyList() // `session:killed`
        runCurrent()

        val detail = tickets.state.value.selected!!
        assertNull(detail.liveSessionId, "Open would jump to a session that is gone")
        assertTrue(detail.canStart, "with nobody on it, the ticket can be started again")
    }

    @Test
    fun a_planned_live_session_that_is_gone_is_not_a_jump() = runTest {
        val actions = FakeWorkActions().apply { planAnswer = plan(live = listOf(LiveWork(sessionId = 12, hostAlias = "pine"))) }
        val tickets = vm(WorkFleet(), actions, backgroundScope, Nav())
        tickets.select(PAY9)
        runCurrent()

        val detail = tickets.state.value.selected!!
        assertNull(detail.liveSessionId)
        assertTrue(detail.canResume, "past work, nobody on it now: Resume")
    }
}
