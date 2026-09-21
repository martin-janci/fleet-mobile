@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
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
    override val sessionChanges = emptyFlow<Long>()

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
    fun the_needs_attention_filter_keeps_only_blocked_or_stuck_rows() = runTest {
        val fleet = FakeFleet(
            rows = listOf(
                session(1, host = "box", claudeStatus = "working"),
                session(2, host = "box", claudeStatus = "blocked"),
                session(3, host = "pine", claudeStatus = "working", stuckKind = "press_enter"),
                session(4, host = "pine", claudeStatus = "completed"),
            ),
        )
        val vm = SessionsViewModel(fleet, backgroundScope)
        assertEquals(4, vm.state.value.groups.sumOf { it.sessionCount })

        vm.toggleNeedsAttentionOnly()
        runCurrent()

        val state = vm.state.value
        assertTrue(state.needsAttentionOnly)
        assertEquals(listOf(2L, 3L), state.groups.flatMap { g -> g.projects.flatMap { it.sessions } }.map { it.id })
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
}
