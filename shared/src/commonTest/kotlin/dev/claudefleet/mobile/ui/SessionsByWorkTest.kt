@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TrackerSummary
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun linked(key: String, itemId: Long? = null, source: String = "branch", unavailable: Boolean = false) =
    WorkSummary(linkId = 1, itemId = itemId, key = key, title = "$key title", source = source, state = "confirmed", unavailable = unavailable)

private fun row(
    id: Long,
    host: String = "box",
    project: Long? = 1,
    work: WorkSummary? = null,
    suggested: WorkSummary? = null,
    kind: String? = "work",
    claudeStatus: String? = "working",
    lastActivityAt: Long? = id,
) = SessionRow(
    id = id,
    tmuxName = "s$id",
    hostAlias = host,
    projectId = project,
    kind = kind,
    claudeStatus = claudeStatus,
    lastActivityAt = lastActivityAt,
    work = work,
    workSuggested = suggested,
)

private fun byWork(vararg rows: SessionRow, hostFilter: String? = null, needsAttentionOnly: Boolean = false, tickets: Map<Long, Ticket> = emptyMap()) =
    groupSessions(rows.toList(), emptyList(), emptyList(), needsAttentionOnly, hostFilter, byWork = true, tickets = tickets)

/**
 * `groupSessions(byWork = true)`: table cases named after the desktop's
 * `buildSessionsByWork` tests (claude-fleet `src/lib/sidebar_index.test.ts`),
 * so the phone and the desktop are checked against the same rules — with the
 * one difference the plan decides: on the phone a work group lives inside its
 * host.
 */
class SessionsByWorkGroupingTest {

    @Test
    fun groups_keyed_sessions_and_leaves_the_rest_to_the_project_tree() {
        val a1 = row(1, project = 1, work = linked("ABC-1"))
        val a2 = row(2, project = 2, work = linked("ABC-1")) // one key across two projects
        val b = row(3, work = linked("DEF-2"))
        val plain = row(4)
        val ext = row(5, kind = "external", work = linked("ABC-1"))

        val host = byWork(a1, plain, b, a2, ext).single()

        assertEquals(
            // DEF-2's session is the most recently active, so it leads.
            listOf("DEF-2" to listOf(3L), "ABC-1" to listOf(2L, 1L)),
            host.work.map { g -> g.key to g.sessions.map { it.id } },
        )
        assertEquals(setOf(4L, 5L), host.projects.flatMap { it.sessions }.map { it.id }.toSet(), "no Unclassified bucket")
        assertEquals(5, host.sessionCount)
    }

    @Test
    fun a_suggestion_never_regroups_only_a_confirmed_link_makes_a_work_group() {
        val suggested = row(1, suggested = linked("ABC-1").copy(state = "suggested"))
        val linkedRow = row(2, work = linked("ABC-1"))
        // A hub that ever put a guess in `work` still does not group it.
        val oddHub = row(3, work = linked("ABC-1").copy(state = "suggested"))

        val host = byWork(suggested, linkedRow, oddHub).single()

        assertEquals(listOf(listOf(2L)), host.work.map { g -> g.sessions.map { it.id } })
        assertEquals(setOf(1L, 3L), host.projects.flatMap { it.sessions }.map { it.id }.toSet())
    }

    /** On the phone a group is per host, so a key on two hosts is two groups, and a host filter keeps its own. */
    @Test
    fun filters_rows_and_groups_per_host() {
        val a1 = row(1, host = "box", work = linked("ABC-1"))
        val a2 = row(2, host = "mefistos", work = linked("ABC-1"))

        assertEquals(listOf("box", "mefistos"), byWork(a1, a2).map { it.alias })
        val filtered = byWork(a1, a2, hostFilter = "mefistos").single()
        assertEquals(listOf(2L), filtered.work.single().sessions.map { it.id })
    }

    @Test
    fun drops_a_group_with_no_visible_session() {
        val quiet = row(1, work = linked("ABC-1"), claudeStatus = "working")
        val waiting = row(2, work = linked("DEF-2"), claudeStatus = "blocked")

        val host = byWork(quiet, waiting, needsAttentionOnly = true).single()

        assertEquals(listOf("DEF-2"), host.work.map { it.key })
        assertTrue(host.projects.isEmpty())
    }

    @Test
    fun sorts_groups_with_someone_waiting_first_keeping_recency_on_ties() {
        val old = row(1, work = linked("A-1"), lastActivityAt = 10)
        val recent = row(2, work = linked("B-1"), lastActivityAt = 50)
        val waiting = row(3, work = linked("C-1"), claudeStatus = "blocked", lastActivityAt = 1)

        val keys = byWork(old, recent, waiting).single().work.map { it.key }

        assertEquals(listOf("C-1", "B-1", "A-1"), keys)
        assertEquals(1, byWork(old, recent, waiting).single().work.first().attentionCount)
    }

    @Test
    fun a_key_with_an_unavailable_ticket_is_still_a_group_and_says_so() {
        val gone = row(1, work = linked("ABC-1", unavailable = true))
        val group = byWork(gone).single().work.single()
        assertTrue(group.unavailable)
        assertEquals("ABC-1 title", group.title)
    }

    /** A `work:item` frame does not restamp the session rows, so the cache's newer word wins on the heading. */
    @Test
    fun the_ticket_cache_overlays_status_and_title() {
        val r = row(1, work = linked("ABC-1", itemId = 9).copy(statusCategory = StatusCategory.Todo))
        val cache = mapOf(9L to Ticket(id = 9, key = "ABC-1", title = "Renamed", statusCategory = StatusCategory.Done, unavailableAt = 5))

        val group = byWork(r, tickets = cache).single().work.single()

        assertEquals("Renamed", group.title)
        assertEquals(StatusCategory.Done, group.status)
        assertTrue(group.unavailable)
    }

    @Test
    fun without_by_work_nothing_is_grouped_by_work() {
        val groups = groupSessions(listOf(row(1, work = linked("ABC-1"))), emptyList(), emptyList(), false)
        assertTrue(groups.single().work.isEmpty())
        assertEquals(listOf(1L), groups.single().projects.single().sessions.map { it.id })
    }

    @Test
    fun my_work_keeps_only_sessions_on_those_items() {
        val mine = row(1, work = linked("ABC-1", itemId = 9))
        val theirs = row(2, work = linked("DEF-2", itemId = 10))
        val bare = row(3)

        val groups = groupSessions(listOf(mine, theirs, bare), emptyList(), emptyList(), false, myWorkItemIds = setOf(9L))

        assertEquals(listOf(1L), groups.single().projects.flatMap { it.sessions }.map { it.id })
        assertTrue(groupSessions(listOf(theirs), emptyList(), emptyList(), false, myWorkItemIds = setOf(9L)).isEmpty())
    }
}

private class WorkFleet(rows: List<SessionRow>) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(emptyList<dev.claudefleet.mobile.model.HostRow>())
    override val projects = MutableStateFlow(emptyList<dev.claudefleet.mobile.model.ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.2.40"))
    override val hubVersion = MutableStateFlow<String?>("0.2.40")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = emptyFlow<Long>()
    val caps = MutableStateFlow(HubCapabilities())
    override val capabilities: StateFlow<HubCapabilities> = caps
    val cache = MutableStateFlow<Map<Long, Ticket>>(emptyMap())
    override val tickets: StateFlow<Map<Long, Ticket>> = cache
    override fun remember(tickets: List<Ticket>) {
        cache.value = cache.value + tickets.associateBy { it.id }
    }
    var refreshes = 0
    override suspend fun refresh() {
        refreshes += 1
    }
}

private class FakeWork(
    var trackers: List<TrackerSummary> = listOf(TrackerSummary(id = 1, provider = "jira", name = "acme", state = "ok")),
    var mine: List<Ticket> = listOf(Ticket(id = 9, key = "ABC-1")),
) : WorkActions {
    var fail: Throwable? = null
    var ticketCalls = 0
    override suspend fun trackers(): List<TrackerSummary> {
        fail?.let { throw it }
        return trackers
    }
    override suspend fun tickets(view: String): List<Ticket> {
        assertEquals("mine", view)
        ticketCalls += 1
        return mine
    }
}

private val WORK_HUB = HubCapabilities(known = true, tools = setOf("work", "work_link"))

class SessionsByWorkViewModelTest {

    @Test
    fun an_old_hub_shows_no_work_chips_and_ignores_a_remembered_toggle() = runTest {
        val prefs = FakePrefs().apply { putStringList("sessions_by_work", listOf("true")) }
        val fleet = WorkFleet(listOf(row(1, work = linked("ABC-1"))))
        val vm = SessionsViewModel(fleet, backgroundScope, work = FakeWork(), prefs = prefs, clock = { 0 })
        runCurrent()

        val s = vm.state.value
        assertFalse(s.workAvailable)
        assertFalse(s.byWork, "no `work` tool, no grouping by it")
        assertFalse(s.myWorkAvailable)
        assertTrue(s.groups.single().work.isEmpty())
    }

    @Test
    fun by_work_groups_the_list_and_is_remembered_on_the_device() = runTest {
        val prefs = FakePrefs()
        val fleet = WorkFleet(listOf(row(1, work = linked("ABC-1")), row(2)))
        fleet.caps.value = WORK_HUB
        val vm = SessionsViewModel(fleet, backgroundScope, work = FakeWork(), prefs = prefs, clock = { 0 })
        runCurrent()
        assertTrue(vm.state.value.workAvailable)
        assertFalse(vm.state.value.byWork)

        vm.toggleByWork()
        runCurrent()

        assertTrue(vm.state.value.byWork)
        assertEquals(listOf("ABC-1"), vm.state.value.groups.single().work.map { it.key })
        assertEquals(listOf("true"), prefs.getStringList("sessions_by_work"))

        val again = SessionsViewModel(fleet, backgroundScope, work = FakeWork(), prefs = prefs, clock = { 0 })
        runCurrent()
        assertTrue(again.state.value.byWork, "a new screen starts where the person left it")

        again.toggleByWork()
        assertEquals(emptyList(), prefs.getStringList("sessions_by_work"))
    }

    @Test
    fun my_work_appears_with_a_tracker_and_filters_to_the_persons_tickets() = runTest {
        val fleet = WorkFleet(listOf(row(1, work = linked("ABC-1", itemId = 9)), row(2, work = linked("DEF-2", itemId = 10))))
        val work = FakeWork()
        val vm = SessionsViewModel(fleet, backgroundScope, work = work, prefs = FakePrefs(), clock = { 0 })
        runCurrent()
        assertFalse(vm.state.value.myWorkAvailable, "nothing loads before the hub is known to serve `work`")

        fleet.caps.value = WORK_HUB
        runCurrent()
        assertTrue(vm.state.value.myWorkAvailable)
        assertEquals(setOf(9L), fleet.cache.value.keys, "what \"mine\" answered is cached for the ticket sheet")

        vm.toggleMyWorkOnly()
        runCurrent()
        assertTrue(vm.state.value.myWorkOnly)
        assertEquals(listOf(1L), vm.state.value.groups.flatMap { h -> h.projects.flatMap { it.sessions } }.map { it.id })
    }

    @Test
    fun no_tracker_no_my_work() = runTest {
        val fleet = WorkFleet(listOf(row(1)))
        fleet.caps.value = WORK_HUB
        val work = FakeWork(trackers = emptyList())
        val vm = SessionsViewModel(fleet, backgroundScope, work = work, prefs = FakePrefs(), clock = { 0 })
        runCurrent()

        assertTrue(vm.state.value.workAvailable)
        assertFalse(vm.state.value.myWorkAvailable)
        assertEquals(0, work.ticketCalls, "no tracker, nothing to ask for")
        vm.toggleMyWorkOnly()
        runCurrent()
        assertFalse(vm.state.value.myWorkOnly, "a hidden chip filters nothing")
        assertEquals(1, vm.state.value.groups.size)
    }

    /** "My work" never costs the list its rows: a failure keeps what was known. */
    @Test
    fun a_failed_reload_keeps_my_work_as_it_was() = runTest {
        val fleet = WorkFleet(listOf(row(1, work = linked("ABC-1", itemId = 9))))
        fleet.caps.value = WORK_HUB
        val work = FakeWork()
        val vm = SessionsViewModel(fleet, backgroundScope, work = work, prefs = FakePrefs(), clock = { 0 })
        runCurrent()
        assertTrue(vm.state.value.myWorkAvailable)

        work.fail = HubError.Tool("E_UNAVAILABLE", "tracker down")
        vm.refresh()
        runCurrent()

        assertTrue(vm.state.value.myWorkAvailable)
        assertEquals(null, vm.state.value.error, "the list's own refresh worked")
        assertEquals(1, fleet.refreshes)
        assertEquals(1, work.ticketCalls, "the reload failed at `trackers`, before asking for tickets")
    }
}
