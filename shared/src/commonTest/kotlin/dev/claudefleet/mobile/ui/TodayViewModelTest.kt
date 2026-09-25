package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.TodayGroup
import dev.claudefleet.mobile.model.TodaySession
import dev.claudefleet.mobile.model.TodayShipped
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Today sheet (claude-fleet M9.1 on the phone): offered only when the hub
 * lists `work today`, read since local midnight, narrowed to the Sessions
 * list's org filter, and kept current while open.
 */
class TodayViewModelTest {
    /** 2026-09-25 12:30 UTC. */
    private val noon = 1_790_339_400L
    private val midnight = 1_790_294_400L

    private val digest = Today(
        since = midnight,
        now = noon,
        groups = listOf(
            TodayGroup(bucket = "waiting", key = "PAY-7", title = "Refund", sessions = listOf(TodaySession(id = 1, name = "pay", attention = "waiting"), TodaySession(id = 2, name = "side"))),
        ),
        shipped = listOf(TodayShipped(how = "done", key = "PAY-3", title = "Receipts", at = noon, orgId = 1)),
    )

    private val rows = listOf(SessionRow(id = 1, orgId = 1), SessionRow(id = 2, orgId = 2))

    private fun vm(
        fleet: WorkFleet,
        actions: FakeWorkActions,
        scope: kotlinx.coroutines.CoroutineScope,
        org: MutableStateFlow<Long?> = MutableStateFlow(null),
        opened: MutableList<Long> = mutableListOf(),
        offset: Int = 0,
    ) = TodayViewModel(fleet, actions, scope, org, { opened += it }, clock = { noon }, utcOffset = { offset }, refreshDebounceMs = 1_000)

    @Test
    fun it_is_offered_only_when_the_hub_lists_today() = runTest {
        val old = HubCapabilities.of(ToolCatalog(setOf("work"), mapOf("work" to setOf("tickets", "links"))))
        val actions = FakeWorkActions()
        val t = vm(WorkFleet(rows, caps = old), actions, backgroundScope)
        runCurrent()
        assertFalse(t.state.value.available)
        assertNull(t.open())
        assertEquals(emptyList(), actions.todayCalls)
    }

    @Test
    fun it_reads_since_local_midnight_and_shows_the_hubs_sections() = runTest {
        val actions = FakeWorkActions().apply { todayAnswer = digest }
        val t = vm(WorkFleet(rows), actions, backgroundScope, offset = 2 * 3600)
        t.open()!!.join()
        runCurrent()

        // Local 14:30 two hours east of UTC: the day began at 22:00 UTC.
        assertEquals(listOf(midnight - 2 * 3600), actions.todayCalls)
        val s = t.state.value
        assertTrue(s.open && s.loaded)
        assertEquals(listOf("PAY-7"), s.view.waiting.map { it.key })
        assertEquals(listOf("PAY-3"), s.view.shipped.map { it.key })
        assertTrue(s.standup.startsWith("Shipped\n- PAY-3 Receipts — done"))
    }

    /** The same org filter as the Sessions list: org 2 keeps only `side`, which needs nobody. */
    @Test
    fun it_shows_the_slice_the_sessions_list_is_filtered_to() = runTest {
        val actions = FakeWorkActions().apply { todayAnswer = digest }
        val org = MutableStateFlow<Long?>(2)
        val t = vm(WorkFleet(rows), actions, backgroundScope, org = org)
        t.open()!!.join()
        runCurrent()

        assertEquals(emptyList(), t.state.value.view.waiting)
        assertEquals(listOf(listOf(2L)), t.state.value.view.inProgress.map { g -> g.sessions.map { it.id } })
        assertEquals(emptyList(), t.state.value.view.shipped)

        org.value = null
        runCurrent()
        assertEquals(listOf("PAY-7"), t.state.value.view.waiting.map { it.key })
    }

    /** While open, a burst of frames costs one re-read; once closed, none. */
    @Test
    fun it_re_reads_once_per_burst_while_open_and_never_after_close() = runTest {
        val actions = FakeWorkActions().apply { todayAnswer = digest }
        val fleet = WorkFleet(rows)
        val t = vm(fleet, actions, backgroundScope)
        t.open()!!.join()
        runCurrent()
        assertEquals(1, actions.todayCalls.size)

        fleet.sessionChanges.tryEmit(1)
        fleet.sessionChanges.tryEmit(2)
        fleet.tickets.value = listOf(Ticket(id = 70, key = "PAY-7", statusName = "Done"))
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(2, actions.todayCalls.size, "one re-read for the burst")

        t.close()
        fleet.sessionChanges.tryEmit(1)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, actions.todayCalls.size, "a closed sheet reads nothing")
    }

    @Test
    fun a_session_line_closes_the_sheet_and_opens_the_session() = runTest {
        val opened = mutableListOf<Long>()
        val actions = FakeWorkActions().apply { todayAnswer = digest }
        val t = vm(WorkFleet(rows), actions, backgroundScope, opened = opened)
        t.open()!!.join()
        t.openSession(1)
        runCurrent()
        assertEquals(listOf(1L), opened)
        assertFalse(t.state.value.open)
    }

    /** A hub whose `action` is a free string and that does not know `today`: the sheet goes for the connection. */
    @Test
    fun an_unknown_action_hides_today_for_the_connection() = runTest {
        val fleet = WorkFleet(rows)
        val actions = FakeWorkActions().apply { failToday = HubError.Tool("E_INVALID", "unknown work action \"today\"; one of links") }
        val t = vm(fleet, actions, backgroundScope)
        t.open()!!.join()
        runCurrent()
        assertFalse(fleet.capabilities.value.has("work", "today"))
        assertFalse(t.state.value.available)
    }

    @Test
    fun a_failed_read_says_so_and_keeps_the_sheet_open() = runTest {
        val actions = FakeWorkActions().apply { failToday = HubError.Tool("E_INTERNAL", "db locked") }
        val t = vm(WorkFleet(rows), actions, backgroundScope)
        t.open()!!.join()
        runCurrent()
        assertTrue(t.state.value.open)
        assertFalse(t.state.value.loaded)
        assertTrue(t.state.value.error != null)
    }
}
