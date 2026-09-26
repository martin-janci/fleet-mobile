@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionFilters
import dev.claudefleet.mobile.model.StatusFilter
import dev.claudefleet.mobile.model.TimeWindow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fleet picture a screen sees, driven by the test rather than by a hub.
 *
 * The view model talks to [FleetState], not to [dev.claudefleet.mobile.data.FleetRepository],
 * which is what lets a grouping test be four lines of setup instead of a mock
 * engine and a fake event stream.
 */
private class FakeFleet(
    rows: List<SessionRow> = emptyList(),
    hostRows: List<HostRow> = emptyList(),
    projectRows: List<ProjectRow> = emptyList(),
) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow(projectRows)
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = emptyFlow<Long>()
    override val capabilities = MutableStateFlow(HubCapabilities())
    override val myWork = MutableStateFlow<Set<Long>?>(null)
    override val tickets = MutableStateFlow<List<dev.claudefleet.mobile.model.Ticket>>(emptyList())
    override val orgs = MutableStateFlow(OrgDirectory.EMPTY)

    var refreshes = 0
        private set

    /** Held open, a refresh stays in flight so the spinner can be observed. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown once the gate opens. */
    var failWith: Throwable? = null

    override suspend fun refresh() {
        refreshes += 1
        gate?.await()
        failWith?.let { throw it }
    }
}

/**
 * `runCurrent()` after every change: `state` is a `stateIn` of a `combine`, so
 * its value is one dispatch behind whatever moved. On a screen that is a frame;
 * in a test it is the difference between reading the answer and reading the
 * question.
 */
private fun session(
    id: Long,
    host: String = "box",
    project: Long? = 1,
    name: String = "s$id",
    claudeStatus: String? = "working",
    stuckKind: String? = null,
    activity: String? = null,
    lastActivityAt: Long? = id,
) = SessionRow(
    id = id,
    tmuxName = name,
    hostAlias = host,
    projectId = project,
    claudeStatus = claudeStatus,
    stuckKind = stuckKind,
    currentActivity = activity,
    lastActivityAt = lastActivityAt,
)

class SessionsViewModelTest {

    @Test
    fun sessions_are_grouped_by_host_then_project() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", project = 1),
                session(2, host = "box", project = 2),
                session(3, host = "pine", project = 1),
                session(4, host = "box", project = 1),
            ),
            hostRows = listOf(HostRow(alias = "box", reachable = true), HostRow("pine")),
            projectRows = listOf(
                ProjectRow(id = 1, owner = "martin-janci", repo = "claude-fleet"),
                ProjectRow(id = 2, owner = "martin-janci", repo = "fleet-mobile"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        val groups = vm.state.value.groups
        assertEquals(listOf("box", "pine"), groups.map { it.alias })
        assertEquals(
            listOf("martin-janci/claude-fleet", "martin-janci/fleet-mobile"),
            groups[0].projects.map { it.label },
        )
        assertEquals(listOf(4L, 1L), groups[0].projects[0].sessions.map { it.id })
        assertEquals(listOf(2L), groups[0].projects[1].sessions.map { it.id })
        assertEquals(listOf("martin-janci/claude-fleet"), groups[1].projects.map { it.label })
        assertEquals(listOf(3L), groups[1].projects[0].sessions.map { it.id })
    }

    /**
     * A session's row carries `project_id` and no name for it — the name is
     * `list_projects`' business — so a project the app has not been told about
     * still has to be a group a person can read.
     */
    @Test
    fun a_project_the_hub_has_not_named_is_still_its_own_group() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1, project = 7)))
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(listOf("project #7"), vm.state.value.groups.single().projects.map { it.label })
    }

    /** A shell session belongs to no project at all, and goes last. */
    @Test
    fun sessions_with_no_project_are_their_own_group_and_come_last() = runTest {
        val fleet = FakeFleet(
            rows = listOf(session(1, project = null), session(2, project = 1)),
            projectRows = listOf(ProjectRow(id = 1, owner = "o", repo = "zzz")),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        val projects = vm.state.value.groups.single().projects
        assertEquals(listOf("o/zzz", "No project"), projects.map { it.label })
        assertNull(projects[1].projectId)
    }

    @Test
    fun the_needs_attention_filter_keeps_only_rows_that_need_a_person() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "working"),
                session(2, host = "box", claudeStatus = "blocked"),
                session(3, host = "pine", claudeStatus = "working", stuckKind = "press_enter"),
                session(4, host = "pine", claudeStatus = "completed"),
                // Failed is the hub's third reason; the old blocked-or-stuck
                // check let it pass as fine.
                session(5, host = "pine", claudeStatus = "failed"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)
        assertEquals(5, vm.state.value.groups.sumOf { it.sessionCount })

        vm.toggleNeedsAttentionOnly()
        runCurrent()

        val state = vm.state.value
        assertTrue(state.needsAttentionOnly)
        assertEquals(setOf(2L, 3L, 5L), state.groups.flatMap { g -> g.projects.flatMap { it.sessions } }.map { it.id }.toSet())
        // Both hosts keep a group because both had a row that matched; a host
        // whose rows all filtered out must disappear rather than show empty.
        assertEquals(listOf("box", "pine"), state.groups.map { it.alias })
    }

    @Test
    fun a_host_with_nothing_to_attend_to_drops_out_of_the_filtered_list() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "working"),
                session(2, host = "pine", claudeStatus = "blocked"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.toggleNeedsAttentionOnly()
        runCurrent()

        assertEquals(listOf("pine"), vm.state.value.groups.map { it.alias })
    }

    /** The toggle's badge counts the whole fleet, not what the filter left. */
    @Test
    fun the_attention_count_is_of_the_whole_fleet_even_while_filtered() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, claudeStatus = "working"),
                session(2, claudeStatus = "blocked"),
                session(3, claudeStatus = "working", stuckKind = "oom"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)
        assertEquals(2, vm.state.value.attentionCount)

        vm.toggleNeedsAttentionOnly()
        runCurrent()

        assertEquals(2, vm.state.value.attentionCount)
    }

    /** Filtering is a view over rows already in hand; it must not cost a call. */
    @Test
    fun toggling_the_filter_does_not_talk_to_the_hub() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1)))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.toggleNeedsAttentionOnly()
        vm.toggleNeedsAttentionOnly()
        runCurrent()

        assertEquals(0, fleet.refreshes)
    }

    @Test
    fun a_row_the_event_stream_changes_reaches_the_screen() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1, claudeStatus = "working")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        fleet.sessions.value = listOf(session(1, claudeStatus = "blocked", activity = "waiting on you"))
        runCurrent()

        val row = vm.state.value.groups.single().projects.single().sessions.single()
        assertEquals("blocked", row.claudeStatus)
        assertEquals("waiting on you", row.currentActivity)
        assertEquals(1, vm.state.value.attentionCount)
    }

    @Test
    fun a_refresh_that_fails_says_so_and_leaves_the_rows_on_screen() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1)))
        fleet.failWith = HubError.Tool("E_NOTFOUND", "no such session")
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.refresh().join()
        runCurrent()

        val state = vm.state.value
        assertFalse(state.refreshing)
        assertEquals("E_NOTFOUND: no such session", state.error?.details)
        assertEquals(1, state.groups.sumOf { it.sessionCount })
    }


    /**
     * Review N-B1: the banner can be put away.
     *
     * It could not be, on three of the five screens. A failed call left its
     * sentence on screen until the next one succeeded, and on a hub that is
     * down that is never — an error a person has read and cannot dismiss is how
     * people learn to stop reading the banner.
     */
    @Test
    fun a_failure_can_be_dismissed_without_the_rows_going_with_it() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1)))
        fleet.failWith = HubError.Tool("E_NOTFOUND", "no such session")
        val vm = SessionsViewModel(fleet, backgroundScope)
        vm.refresh().join()
        runCurrent()
        assertEquals("E_NOTFOUND: no such session", vm.state.value.error?.details)

        vm.dismissError()
        // The screen's state is assembled from `local` and the fleet flows by a
        // `combine(...).stateIn(...)`, so the new value lands on the next turn
        // of the test dispatcher rather than inside `dismissError`.
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(1, vm.state.value.groups.sumOf { it.sessionCount }, "the rows stay")
    }

    @Test
    fun a_refresh_is_marked_in_flight_until_it_returns() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1)))
        val gate = CompletableDeferred<Unit>()
        fleet.gate = gate
        val vm = SessionsViewModel(fleet, backgroundScope)

        val job = vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.refreshing, "the screen must be able to say it is refreshing")

        gate.complete(Unit)
        job.join()
        runCurrent()
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_refresh_that_works_clears_the_last_failure() = runTest {
        val fleet = FakeFleet(rows = listOf(session(1)))
        fleet.failWith = HubError.Unauthorized()
        val vm = SessionsViewModel(fleet, backgroundScope)
        vm.refresh().join()
        runCurrent()
        assertTrue(vm.state.value.error != null)

        fleet.failWith = null
        vm.refresh().join()
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(2, fleet.refreshes)
    }

    @Test
    fun the_connection_banner_follows_the_repository() = runTest {
        val fleet = FakeFleet()
        val vm = SessionsViewModel(fleet, backgroundScope)
        assertEquals(ConnectionStatus.Connected("0.9.3"), vm.state.value.status)

        fleet.status.value = ConnectionStatus.Offline("revoked")
        runCurrent()

        assertEquals(ConnectionStatus.Offline("revoked"), vm.state.value.status)
    }

    /**
     * A phone is held in one hand, so the row most likely to be wanted is the
     * one that moved most recently. Nulls sort last: a row the hub has never
     * stamped is not a row that just did something.
     */
    @Test
    fun sessions_inside_a_project_are_most_recently_active_first() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, lastActivityAt = 50),
                session(2, lastActivityAt = null),
                session(3, lastActivityAt = 900),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(
            listOf(3L, 1L, 2L),
            vm.state.value.groups.single().projects.single().sessions.map { it.id },
        )
    }

    /** The pure grouping function, filtered to one host directly — no view model involved. */
    @Test
    fun grouping_can_be_filtered_to_one_host() {
        val groups = groupSessions(
            sessions = listOf(session(1, host = "box"), session(2, host = "pine")),
            hosts = emptyList(),
            projects = emptyList(),
            filters = SessionFilters(hostFilter = "pine"),
        )
        assertEquals(listOf("pine"), groups.map { it.alias })
    }

    /** A host with no sessions of its own is simply not there, same as any other empty group. */
    @Test
    fun grouping_filtered_to_a_host_with_nothing_on_it_is_empty() {
        val groups = groupSessions(
            sessions = listOf(session(1, host = "box")),
            hosts = emptyList(),
            projects = emptyList(),
            filters = SessionFilters(hostFilter = "pine"),
        )
        assertTrue(groups.isEmpty())
    }

    /**
     * Tapping a host row shows only that host's groups, but the badge in the
     * bar still counts the whole fleet — matching how [toggleNeedsAttentionOnly]
     * already treats [SessionsUiState.attentionCount].
     */
    @Test
    fun a_host_filter_keeps_only_that_hosts_groups_while_the_attention_count_stays_fleetwide() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "blocked"),
                session(2, host = "pine", claudeStatus = "working"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)
        assertEquals(1, vm.state.value.attentionCount)

        vm.setHostFilter("pine")
        runCurrent()

        val filtered = vm.state.value
        assertEquals(listOf("pine"), filtered.groups.map { it.alias })
        assertEquals("pine", filtered.hostFilter)
        assertEquals(1, filtered.attentionCount, "fleet-wide, unaffected by the host filter")

        vm.setHostFilter(null)
        runCurrent()

        val cleared = vm.state.value
        assertEquals(listOf("box", "pine"), cleared.groups.map { it.alias })
        assertNull(cleared.hostFilter)
    }

    @Test
    fun a_fleet_with_no_sessions_says_so_rather_than_drawing_an_empty_group() = runTest {
        val vm = SessionsViewModel(FakeFleet(hostRows = listOf(HostRow("box"))), backgroundScope)

        assertTrue(vm.state.value.groups.isEmpty())
        assertTrue(vm.state.value.isEmpty)
    }

    /** `list_hosts` is what knows whether a host answers; the session rows do not. */
    @Test
    fun a_host_group_carries_the_reachability_the_host_list_reported() = runTest {
        val fleet = FakeFleet(
            rows = listOf(session(1, host = "box"), session(2, host = "ghost")),
            hostRows = listOf(HostRow(alias = "box", reachable = true)),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        val groups = vm.state.value.groups.associateBy { it.alias }
        assertEquals(true, groups.getValue("box").reachable)
        // Not in `list_hosts` at all: unknown, which is not the same as "down".
        assertNull(groups.getValue("ghost").reachable)
    }

    /**
     * `nowSeconds` is what the row's age and every `relativeTime` on screen are
     * computed against. It has to be injectable — a real clock would make this
     * test flaky and slow — and it has to tick on its own, every 30s, so a row
     * left open gets visibly older without a refresh.
     */
    @Test
    fun the_state_carries_a_clock_that_ticks() = runTest {
        var now = 1_000L
        val vm = SessionsViewModel(FakeFleet(), backgroundScope, clock = { now })
        val first = vm.state.first { it.nowSeconds > 0 }
        assertEquals(1_000L, first.nowSeconds)
        now = 1_040L
        advanceTimeBy(31_000)
        assertEquals(1_040L, vm.state.value.nowSeconds)
    }
}


/**
 * The needs-attention filter, through the method the bar is actually wired to.
 *
 * `SessionsScreen` calls `toggleNeedsAttentionOnly`, and nothing in the app ever
 * called `setNeedsAttentionOnly(on)` — but every test did. So the path being
 * exercised was not the path that ships, which is the arrangement that lets a
 * bug live in the gap between them. The setter is gone and these go through the
 * toggle.
 */
class NeedsAttentionToggleTest {

    @Test
    fun the_toggle_turns_the_filter_on_and_off_again() = runTest {
        val fleet = FakeFleet(listOf(session(1, claudeStatus = "blocked"), session(2)))
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(2, vm.state.value.groups.sumOf { it.sessionCount })

        vm.toggleNeedsAttentionOnly()
        runCurrent()
        assertTrue(vm.state.value.needsAttentionOnly)
        assertEquals(
            listOf(1L),
            vm.state.value.groups.flatMap { g -> g.projects.flatMap { it.sessions } }.map { it.id },
        )

        vm.toggleNeedsAttentionOnly()
        runCurrent()
        assertFalse(vm.state.value.needsAttentionOnly)
        assertEquals(2, vm.state.value.groups.sumOf { it.sessionCount })
    }

    /**
     * Two taps in a row land on two different answers.
     *
     * The flip reads and writes in one `update {}` rather than reading
     * `local.value` and then writing, so two calls cannot both observe the same
     * value and both write the same result — which would swallow one tap and
     * leave the switch disagreeing with the list under it.
     */
    @Test
    fun every_tap_moves_the_filter() = runTest {
        val fleet = FakeFleet(listOf(session(1, claudeStatus = "blocked"), session(2)))
        val vm = SessionsViewModel(fleet, backgroundScope)

        // Two taps with nothing in between — no `runCurrent()`, so the `stateIn`
        // collector has not run and `state` still reads false throughout. That
        // is the whole point: a toggle that decided from `state.value` would
        // see false twice, write true twice, and swallow the second tap. One
        // `update {}` reads the value it is writing against, so the pair
        // cancels out.
        vm.toggleNeedsAttentionOnly()
        vm.toggleNeedsAttentionOnly()
        runCurrent()

        assertFalse(vm.state.value.needsAttentionOnly, "two taps cancel; neither may be lost")

        vm.toggleNeedsAttentionOnly()
        runCurrent()
        assertTrue(vm.state.value.needsAttentionOnly, "and a third still flips it")
    }

    /** The count in the bar is the whole fleet's, filtered or not. */
    @Test
    fun the_attention_count_ignores_the_filter() = runTest {
        val fleet = FakeFleet(listOf(session(1, claudeStatus = "blocked"), session(2)))
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(1, vm.state.value.attentionCount)
        vm.toggleNeedsAttentionOnly()
        runCurrent()
        assertEquals(1, vm.state.value.attentionCount, "it counts the fleet, not the filtered view")
    }

    // ---- by work (M8.2): the desktop's `buildSessionsByWork` cases, by name ----

    private fun linked(key: String, itemId: Long? = null, unavailable: Boolean = false) =
        WorkSummary(linkId = key.hashCode().toLong(), itemId = itemId, key = key, title = "$key title", unavailable = unavailable)

    private fun keyed(id: Long, key: String, project: Long? = 1, host: String = "box", claudeStatus: String? = "working") =
        session(id, host = host, project = project, claudeStatus = claudeStatus).copy(work = linked(key))

    private fun Pair<List<HostGroup>, String>.labels() =
        first.single { it.alias == second }.projects.map { (if (it.work != null) "work:" else "") + it.label to it.sessions.map { s -> s.id } }

    /** Desktop: "groups keyed sessions and leaves the rest to the project tree" — one key across two projects is one group. */
    @Test
    fun groups_keyed_sessions_and_leaves_the_rest_to_the_project_tree() {
        val rows = listOf(
            keyed(1, "ABC-1", project = 1),
            session(2, project = 1),
            keyed(3, "DEF-2", project = 1),
            keyed(4, "ABC-1", project = 2),
            keyed(5, "ABC-1", project = 1).copy(kind = "external"),
        )
        val groups = groupSessions(rows, emptyList(), emptyList(), byWork = true)

        assertEquals(
            listOf("work:ABC-1" to listOf(4L, 1L), "work:DEF-2" to listOf(3L), "project #1" to listOf(5L, 2L)),
            (groups to "box").labels(),
            "an external session is never grouped; it stays with its project",
        )
    }

    /** Desktop: "a suggestion never regroups: only a confirmed link makes a work group (M4.4)". */
    @Test
    fun a_suggestion_never_regroups_only_a_confirmed_link_makes_a_work_group() {
        val suggested = session(1).copy(workSuggested = linked("ABC-1"))
        val confirmed = keyed(2, "ABC-1")
        val groups = groupSessions(listOf(suggested, confirmed), emptyList(), emptyList(), byWork = true)

        assertEquals(listOf("work:ABC-1" to listOf(2L), "project #1" to listOf(1L)), (groups to "box").labels())
    }

    /**
     * Desktop: "filters rows but still reports every keyed session" — a keyed
     * session hidden by a filter must not reappear under its project header.
     */
    @Test
    fun filters_rows_but_a_hidden_keyed_session_never_reappears_under_its_project() {
        val rows = listOf(keyed(1, "ABC-1"), keyed(2, "ABC-1", host = "mefistos"), keyed(3, "ABC-1", claudeStatus = "blocked"))

        val onHost = groupSessions(rows, emptyList(), emptyList(), filters = SessionFilters(hostFilter = "mefistos"), byWork = true)
        assertEquals(listOf("work:ABC-1" to listOf(2L)), (onHost to "mefistos").labels())

        val attention = groupSessions(rows, emptyList(), emptyList(), filters = SessionFilters(needsAttentionOnly = true), byWork = true)
        assertEquals(listOf("work:ABC-1" to listOf(3L)), (attention to "box").labels(), "session 1 is filtered out of both kinds of group")
    }

    /** Desktop: "drops a group with no visible session". */
    @Test
    fun drops_a_group_with_no_visible_session() {
        val rows = listOf(keyed(1, "ABC-1"), keyed(2, "DEF-2", claudeStatus = "blocked"))
        val groups = groupSessions(rows, emptyList(), emptyList(), filters = SessionFilters(needsAttentionOnly = true), byWork = true)

        assertEquals(listOf("work:DEF-2" to listOf(2L)), (groups to "box").labels())
    }

    /** Desktop: "sorts groups by worst severity, keeping recency order on ties" — severity here is who needs a person. */
    @Test
    fun sorts_groups_by_attention_keeping_recency_order_on_ties() {
        val rows = listOf(
            keyed(1, "A-1"),
            keyed(2, "B-2", claudeStatus = "blocked"),
            keyed(3, "C-3"),
            keyed(4, "B-2"),
        )
        val groups = groupSessions(rows, emptyList(), emptyList(), byWork = true)

        // B-2 wants a person; C-3 (id 3) was active more recently than A-1.
        assertEquals(listOf("work:B-2", "work:C-3", "work:A-1"), (groups to "box").labels().map { it.first })
    }

    /** Plan: a key whose ticket the tracker stopped answering for still groups, and the heading knows. */
    @Test
    fun a_key_with_an_unavailable_ticket_still_groups() {
        val gone = session(1).copy(work = linked("PAY-7", unavailable = true))
        val group = groupSessions(listOf(gone), emptyList(), emptyList(), byWork = true)
            .single().projects.single()

        assertEquals("PAY-7", group.label)
        assertTrue(group.work!!.unavailable)
    }

    /** Keys are compared the way the hub normalises them: `pay-7` and `PAY-7` are one group. */
    @Test
    fun keys_group_case_insensitively() {
        val rows = listOf(keyed(1, "PAY-7"), session(2).copy(work = linked("pay-7")))
        val groups = groupSessions(rows, emptyList(), emptyList(), byWork = true)

        assertEquals(1, groups.single().projects.size)
    }

    @Test
    fun without_by_work_a_keyed_session_stays_in_its_project() {
        val groups = groupSessions(listOf(keyed(1, "ABC-1")), emptyList(), emptyList())
        assertEquals(listOf("project #1" to listOf(1L)), (groups to "box").labels())
    }

    // ---- the view model's work toggles ----

    private fun workFleet(rows: List<SessionRow>) = FakeFleet(rows).apply {
        capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
    }

    @Test
    fun by_work_is_offered_only_by_a_hub_with_the_work_graph_and_is_remembered() = runTest {
        val prefs = FakePrefs()
        val fleet = FakeFleet(listOf(keyed(1, "ABC-1")))
        val vm = SessionsViewModel(fleet, backgroundScope, prefs = prefs)

        vm.setGroupMode(GroupMode.WORK)
        runCurrent()
        assertFalse(vm.state.value.workAvailable)
        assertFalse(vm.state.value.byWork, "an old hub: nothing to group by")

        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
        runCurrent()
        assertTrue(vm.state.value.byWork)
        assertTrue(vm.state.value.groups.single().projects.single().work != null)

        val next = SessionsViewModel(workFleet(listOf(keyed(1, "ABC-1"))), backgroundScope, prefs = prefs)
        assertTrue(next.state.value.byWork, "the choice survives a relaunch")
        next.setGroupMode(GroupMode.PROJECT)
        assertEquals(emptyList(), prefs.getStringList("sessions.by_work"))
    }

    @Test
    fun my_work_keeps_only_sessions_on_those_tickets_and_hides_without_a_tracker() = runTest {
        val fleet = workFleet(
            listOf(session(1).copy(work = linked("PAY-7", itemId = 70)), session(2).copy(work = linked("OPS-1", itemId = 80)), session(3)),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertFalse(vm.state.value.myWorkAvailable, "no tracker: no My work")
        fleet.myWork.value = setOf(70L)
        vm.toggleMyWorkOnly()
        runCurrent()

        assertTrue(vm.state.value.myWorkOnly)
        assertEquals(listOf(1L), vm.state.value.groups.single().projects.flatMap { it.sessions }.map { it.id })

        fleet.myWork.value = null
        runCurrent()
        assertFalse(vm.state.value.myWorkOnly, "the tracker went: the filter goes with its chip")
        assertEquals(3, vm.state.value.groups.single().projects.flatMap { it.sessions }.size)
    }

    /**
     * A ticket moving on the tracker arrives as `work:item`, which updates the
     * ticket cache and not the session rows. The heading and the chips follow
     * the cache rather than keep the status the row was stamped with.
     */
    @Test
    fun a_work_heading_follows_the_ticket_cache() = runTest {
        val fleet = workFleet(listOf(session(1).copy(work = linked("PAY-7", itemId = 70).copy(statusName = "To Do"))))
        val vm = SessionsViewModel(fleet, backgroundScope)
        vm.setGroupMode(GroupMode.WORK)
        runCurrent()
        assertEquals("To Do", vm.state.value.groups.single().projects.single().work!!.statusName)

        fleet.tickets.value = listOf(
            dev.claudefleet.mobile.model.Ticket(id = 70, key = "PAY-7", title = "Refund webhook", statusName = "In Review"),
        )
        runCurrent()

        val work = vm.state.value.groups.single().projects.single().work!!
        assertEquals("In Review", work.statusName)
        assertEquals("Refund webhook", work.title)
        assertEquals("In Review", vm.state.value.groups.single().projects.single().sessions.single().work!!.statusName, "the row's chip too")
    }

    // ---- orgs (M8.6): a way of reading the list, never a scope ----

    private val twoOrgs = OrgDirectory.of(listOf(OrgDetail(1, "Acme"), OrgDetail(2, "Side")))

    @Test
    fun the_org_chips_are_offered_only_for_two_or_more_orgs() = runTest {
        val fleet = workFleet(listOf(session(1).copy(orgId = 1), session(2)))
        fleet.orgs.value = twoOrgs
        val vm = SessionsViewModel(fleet, backgroundScope)
        runCurrent()
        assertEquals(emptyList(), vm.state.value.orgChoices, "one org and an unclaimed session: nothing to choose")

        // A second org, known only through a work link — a hub before M8.6.
        fleet.sessions.value = listOf(session(1).copy(orgId = 1), session(2).copy(work = linked("ENG-2").copy(orgId = 2)))
        runCurrent()
        assertEquals(listOf("Acme", "Side"), vm.state.value.orgChoices.map { it.name })
    }

    @Test
    fun an_org_narrows_the_list_and_tapping_it_again_clears_it() = runTest {
        val fleet = workFleet(listOf(session(1).copy(orgId = 1), session(2).copy(orgId = 2), session(3)))
        fleet.orgs.value = twoOrgs
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.toggleOrg(2)
        runCurrent()
        assertEquals(2L, vm.state.value.orgFilter)
        assertEquals(2L, vm.orgFilter.value, "what the Today sheet scopes itself by")
        assertEquals(listOf(2L), vm.state.value.groups.flatMap { h -> h.projects.flatMap { p -> p.sessions.map { it.id } } })

        vm.toggleOrg(2)
        runCurrent()
        assertNull(vm.state.value.orgFilter)
        assertEquals(3, vm.state.value.groups.flatMap { h -> h.projects.flatMap { it.sessions } }.size)
    }

    /** Like *My work*'s chip: a filter nobody can see is dropped rather than left narrowing the list. */
    @Test
    fun the_filter_goes_with_its_chips() = runTest {
        val fleet = workFleet(listOf(session(1).copy(orgId = 1), session(2).copy(orgId = 2)))
        val vm = SessionsViewModel(fleet, backgroundScope)
        vm.toggleOrg(2)
        runCurrent()
        assertEquals(2L, vm.state.value.orgFilter)

        fleet.sessions.value = listOf(session(1).copy(orgId = 1))
        runCurrent()
        assertNull(vm.state.value.orgFilter)
        assertEquals(1, vm.state.value.groups.single().projects.single().sessions.size)
    }

    /** With several orgs on screen a work heading says which org it is; narrowed to one, it need not. */
    @Test
    fun a_work_heading_names_its_org_while_the_list_shows_several() = runTest {
        val fleet = workFleet(listOf(keyed(1, "PAY-7").copy(orgId = 1), keyed(2, "ENG-2").copy(orgId = 2)))
        fleet.orgs.value = twoOrgs
        val vm = SessionsViewModel(fleet, backgroundScope)
        vm.setGroupMode(GroupMode.WORK)
        runCurrent()
        val labels = vm.state.value.groups.single().projects.associate { it.label to it.orgLabel }
        assertEquals(mapOf("PAY-7" to "Acme", "ENG-2" to "Side"), labels)
        assertEquals("Work PAY-7, PAY-7 title, in Acme", workHeaderDescription(linked("PAY-7"), 0, "Acme"))

        vm.toggleOrg(1)
        runCurrent()
        assertEquals(listOf<String?>(null), vm.state.value.groups.single().projects.map { it.orgLabel })
    }
}

/**
 * The filters the sheet owns, driven through the view model.
 *
 * The narrowing rules themselves are `SessionFiltersTest`'s — this is about
 * what the view model does around them: the counts the screen draws, the clock
 * the window is measured against, and the filters that must not outlive the
 * hub feature that offered them.
 */
class SessionFilterStateTest {

    @Test
    fun the_state_counts_what_is_shown_against_what_the_fleet_has() = runTest {
        val fleet = FakeFleet(listOf(session(1, host = "box"), session(2, host = "pine"), session(3, host = "pine")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(3, vm.state.value.total)
        assertEquals(3, vm.state.value.shown)
        assertFalse(vm.state.value.narrowed, "nothing is on, so nothing is being hidden")

        vm.setHostFilter("pine")
        runCurrent()
        assertEquals(2, vm.state.value.shown)
        assertEquals(3, vm.state.value.total, "the total is the fleet's, not the filtered list's")
        assertTrue(vm.state.value.narrowed)
    }

    /**
     * A filter that happens to hide nothing is not "narrowed": the summary
     * line exists to explain missing rows, and drawing "3 of 3" over a list
     * with nothing missing is chrome that teaches people to ignore it.
     */
    @Test
    fun a_filter_that_hides_nothing_does_not_claim_to() = runTest {
        val fleet = FakeFleet(listOf(session(1, host = "box"), session(2, host = "box")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setHostFilter("box")
        runCurrent()
        assertEquals(2, vm.state.value.shown)
        assertFalse(vm.state.value.narrowed)
        assertEquals(1, vm.state.value.filters.activeCount, "it is still on, it is just not hiding anything")
    }

    /**
     * The one that matters. `nowSeconds` ticks every 30 seconds so ages stay
     * honest, and if the activity window were measured against it, rows would
     * cross the boundary and vanish under a thumb mid-scroll. The window is
     * anchored when it is set and re-anchored on a refresh, so between those
     * two the set of rows changes only because the fleet did.
     */
    @Test
    fun the_activity_window_does_not_move_with_the_ticking_clock() = runTest {
        var now = 10_000L
        // Active 30 seconds ago: inside a one-hour window, and it stays inside
        // for the rest of this test however far the display clock runs.
        val fleet = FakeFleet(listOf(session(1, lastActivityAt = 9_970)))
        val vm = SessionsViewModel(fleet, backgroundScope, clock = { now })

        vm.setWindow(TimeWindow.H1)
        runCurrent()
        assertEquals(1, vm.state.value.shown)

        // Two hours pass on the display clock. The row is now two hours old by
        // the age the row itself draws — and still shown, because the window
        // was anchored when it was asked for.
        now += 7_200
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(10_000L + 7_200, vm.state.value.nowSeconds, "the display clock did tick")
        assertEquals(1, vm.state.value.shown, "the row left the window without anyone touching a control")
    }

    /** Pulling to refresh is the other thing that re-anchors it — that is what a pull asks for. */
    @Test
    fun a_refresh_re_anchors_the_activity_window() = runTest {
        var now = 10_000L
        val fleet = FakeFleet(listOf(session(1, lastActivityAt = 9_970)))
        val vm = SessionsViewModel(fleet, backgroundScope, clock = { now })

        vm.setWindow(TimeWindow.H1)
        runCurrent()
        assertEquals(1, vm.state.value.shown)

        now += 7_200
        vm.refresh().join()
        runCurrent()
        assertEquals(0, vm.state.value.shown, "after a pull, 'within an hour' means an hour from now")
    }

    @Test
    fun setting_a_window_later_measures_from_then_and_not_from_launch() = runTest {
        var now = 10_000L
        val fleet = FakeFleet(listOf(session(1, lastActivityAt = 13_000)))
        val vm = SessionsViewModel(fleet, backgroundScope, clock = { now })

        // The row is stamped in the future of launch and in the past of now.
        now = 16_000
        vm.setWindow(TimeWindow.H1)
        runCurrent()
        assertEquals(1, vm.state.value.shown, "3000s old, well inside an hour of when the window was set")
    }

    @Test
    fun the_search_field_clears_its_query_when_it_is_put_away() = runTest {
        val vm = SessionsViewModel(FakeFleet(listOf(session(1))), backgroundScope)

        vm.toggleSearch()
        vm.setQuery("hub")
        runCurrent()
        assertTrue(vm.state.value.searchOpen)
        assertEquals("hub", vm.state.value.filters.query)

        vm.toggleSearch()
        runCurrent()
        assertFalse(vm.state.value.searchOpen)
        assertEquals("", vm.state.value.filters.query, "a filter whose control is off screen cannot be undone")
        assertEquals(0, vm.state.value.filters.activeCount)
    }

    /**
     * `clearFilters` leaves the host alone on purpose — the navigator owns it
     * (`Screen.Sessions.hostAlias`), and `App` calls both. A version of this
     * that cleared the host here would put the two copies into disagreement,
     * which is the stale-`returnTo` bug `Navigator.clearHostFilter` documents.
     */
    @Test
    fun clearing_the_filters_leaves_the_host_for_the_navigator() = runTest {
        val fleet = FakeFleet(listOf(session(1, host = "box"), session(2, host = "pine")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setHostFilter("pine")
        vm.toggleNeedsAttentionOnly()
        vm.setWindow(TimeWindow.D1)
        vm.toggleBackground()
        runCurrent()
        assertEquals(4, vm.state.value.filters.activeCount)

        vm.clearFilters()
        runCurrent()
        assertEquals("pine", vm.state.value.hostFilter)
        assertEquals(1, vm.state.value.filters.activeCount)
        assertEquals(TimeWindow.ANY, vm.state.value.filters.window)
        assertTrue(vm.state.value.filters.showBackground)
    }

    @Test
    fun a_status_can_be_added_and_taken_away_again() = runTest {
        val fleet = FakeFleet(listOf(session(1, claudeStatus = "blocked"), session(2, claudeStatus = "working")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.toggleStatus(StatusFilter.BLOCKED)
        runCurrent()
        assertEquals(1, vm.state.value.shown)

        vm.toggleStatus(StatusFilter.WORKING)
        runCurrent()
        assertEquals(2, vm.state.value.shown, "two statuses are a union, not an intersection")

        vm.toggleStatus(StatusFilter.WORKING)
        vm.toggleStatus(StatusFilter.BLOCKED)
        runCurrent()
        assertEquals(2, vm.state.value.shown, "no status chosen means every status")
        assertEquals(0, vm.state.value.filters.activeCount)
    }

    @Test
    fun background_agents_can_be_left_out() = runTest {
        val fleet = FakeFleet(listOf(session(1), session(2, name = "bg:abcd")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        assertEquals(2, vm.state.value.shown)
        vm.toggleBackground()
        runCurrent()
        assertEquals(1, vm.state.value.shown)
    }

    @Test
    fun the_search_reaches_the_project_name_the_row_does_not_carry() = runTest {
        val fleet = FakeFleet(
            rows = listOf(session(1, project = 1), session(2, project = 2)),
            projectRows = listOf(
                ProjectRow(id = 1, owner = "martin-janci", repo = "claude-fleet"),
                ProjectRow(id = 2, owner = "martin-janci", repo = "fleet-mobile"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setQuery("mobile")
        runCurrent()
        assertEquals(1, vm.state.value.shown)
        assertEquals(listOf(2L), vm.state.value.groups.single().projects.single().sessions.map { it.id })
    }

    /**
     * The same rule the org chips and *By work* already follow: a filter whose
     * control is gone is dropped rather than left narrowing a list with
     * nothing on screen to explain it.
     */
    @Test
    fun my_work_stops_narrowing_when_the_tracker_goes_away() = runTest {
        val fleet = FakeFleet(listOf(session(1), session(2)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
        fleet.myWork.value = setOf(99)
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.toggleMyWorkOnly()
        runCurrent()
        assertTrue(vm.state.value.myWorkOnly)
        assertEquals(0, vm.state.value.shown, "neither session is on ticket 99")

        fleet.myWork.value = null
        runCurrent()
        assertFalse(vm.state.value.myWorkOnly)
        assertEquals(2, vm.state.value.shown)
        assertEquals(0, vm.state.value.filters.activeCount)
    }
}

/** What the filter sheet is given to offer as hosts. */
class HostChoicesTest {

    @Test
    fun the_host_list_is_offered_with_the_reachability_it_reported() {
        val choices = hostChoices(
            sessions = listOf(session(1, host = "box")),
            hosts = listOf(HostRow(alias = "pine", reachable = true), HostRow(alias = "box", reachable = false)),
        )
        assertEquals(listOf("box", "pine"), choices.map { it.alias })
        assertEquals(listOf(false, true), choices.map { it.reachable })
    }

    /**
     * A host only a session names is offered, with **unknown** reachability —
     * not `false`. Leaving it out would make its rows unreachable by the one
     * filter meant to find them, and calling it unreachable is the accusation
     * `HostGroup.reachable` exists to avoid.
     */
    @Test
    fun a_host_only_a_session_names_is_offered_and_is_not_called_unreachable() {
        val choices = hostChoices(listOf(session(1, host = "ghost")), hosts = emptyList())
        assertEquals(listOf("ghost"), choices.map { it.alias })
        assertEquals(listOf<Boolean?>(null), choices.map { it.reachable })
    }

    @Test
    fun a_hidden_host_is_offered_only_while_it_has_sessions_on_screen() {
        val hidden = HostRow(alias = "old", reachable = true, hidden = true)
        assertTrue(hostChoices(emptyList(), listOf(hidden)).isEmpty())
        assertEquals(listOf("old"), hostChoices(listOf(session(1, host = "old")), listOf(hidden)).map { it.alias })
    }
}

/** The three views, and the queue that is not a grouping at all. */
class GroupModeTest {

    @Test
    fun the_urgency_queue_is_flat_and_worst_first() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "working"),
                session(2, host = "pine", claudeStatus = "blocked"),
                session(3, host = "box", claudeStatus = "failed"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setGroupMode(GroupMode.URGENCY)
        runCurrent()
        assertEquals(listOf(2L, 3L, 1L), vm.state.value.urgent.map { it.id })
        assertTrue(vm.state.value.groups.isEmpty(), "the queue has no headings to group under")
        assertEquals(3, vm.state.value.shown)
        assertFalse(vm.state.value.isEmpty, "a screen with a queue on it is not empty")
    }

    /** The queue is a view, not a filter: the same rows the tree would show. */
    @Test
    fun the_queue_narrows_by_exactly_the_same_filters_as_the_tree() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "blocked"),
                session(2, host = "pine", claudeStatus = "blocked"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setHostFilter("pine")
        runCurrent()
        val inTree = vm.state.value.shown

        vm.setGroupMode(GroupMode.URGENCY)
        runCurrent()
        assertEquals(inTree, vm.state.value.shown)
        assertEquals(listOf(2L), vm.state.value.urgent.map { it.id })
    }

    @Test
    fun the_cycle_skips_work_on_a_hub_without_the_work_graph() = runTest {
        val vm = SessionsViewModel(FakeFleet(listOf(session(1))), backgroundScope)

        vm.cycleGroupMode(workAvailable = false)
        runCurrent()
        assertEquals(GroupMode.URGENCY, vm.state.value.groupMode)

        vm.cycleGroupMode(workAvailable = false)
        runCurrent()
        assertEquals(GroupMode.PROJECT, vm.state.value.groupMode)
    }

    @Test
    fun the_cycle_visits_all_three_when_the_hub_has_the_work_graph() = runTest {
        val fleet = FakeFleet(listOf(session(1)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        val seen = buildList {
            repeat(4) {
                vm.cycleGroupMode(workAvailable = true)
                runCurrent()
                add(vm.state.value.groupMode)
            }
        }
        assertEquals(
            listOf(GroupMode.WORK, GroupMode.URGENCY, GroupMode.PROJECT, GroupMode.WORK),
            seen,
        )
    }

    /** `byWork` is still what the screen asks, so the work grouping is unchanged. */
    @Test
    fun the_work_grouping_still_answers_to_by_work() = runTest {
        val fleet = FakeFleet(listOf(session(1)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setGroupMode(GroupMode.WORK)
        runCurrent()
        assertTrue(vm.state.value.byWork)

        vm.setGroupMode(GroupMode.URGENCY)
        runCurrent()
        assertFalse(vm.state.value.byWork, "urgency is not work")
    }

    /** A hub that loses the work graph cannot leave the list in a mode whose chip is gone. */
    @Test
    fun the_work_view_falls_back_to_project_when_the_work_graph_goes_away() = runTest {
        val fleet = FakeFleet(listOf(session(1)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))
        val vm = SessionsViewModel(fleet, backgroundScope)

        vm.setGroupMode(GroupMode.WORK)
        runCurrent()
        assertEquals(GroupMode.WORK, vm.state.value.groupMode)

        fleet.capabilities.value = HubCapabilities()
        runCurrent()
        assertEquals(GroupMode.PROJECT, vm.state.value.groupMode)
    }

    /**
     * A build that has been storing `sessions.by_work` for months is on
     * people's phones. Reading only the new key would silently reset every one
     * of them to project on upgrade.
     */
    @Test
    fun a_view_stored_by_the_older_build_is_still_honoured() = runTest {
        val prefs = FakePrefs()
        prefs.putStringList("sessions.by_work", listOf("on"))
        val fleet = FakeFleet(listOf(session(1)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))

        val vm = SessionsViewModel(fleet, backgroundScope, prefs = prefs)
        runCurrent()
        assertEquals(GroupMode.WORK, vm.state.value.groupMode)
    }

    @Test
    fun the_view_is_remembered_and_the_older_key_is_kept_in_step() = runTest {
        val prefs = FakePrefs()
        val fleet = FakeFleet(listOf(session(1)))
        fleet.capabilities.value = HubCapabilities.of(ToolCatalog(setOf("work")))

        SessionsViewModel(fleet, backgroundScope, prefs = prefs).setGroupMode(GroupMode.WORK)
        assertEquals(listOf("WORK"), prefs.getStringList("sessions.group_mode"))
        assertEquals(listOf("on"), prefs.getStringList("sessions.by_work"))

        // Urgency has no representation in the old key, and is not work.
        SessionsViewModel(fleet, backgroundScope, prefs = prefs).setGroupMode(GroupMode.URGENCY)
        assertEquals(listOf("URGENCY"), prefs.getStringList("sessions.group_mode"))
        assertEquals(emptyList(), prefs.getStringList("sessions.by_work"))

        // And a fresh view model reads it back.
        val reopened = SessionsViewModel(fleet, backgroundScope, prefs = prefs)
        runCurrent()
        assertEquals(GroupMode.URGENCY, reopened.state.value.groupMode)
    }
}
