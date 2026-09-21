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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFleetForHosts(
    hostRows: List<HostRow> = emptyList(),
    sessionRows: List<SessionRow> = emptyList(),
) : FleetState {
    override val sessions = MutableStateFlow(sessionRows)
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val sessionChanges = emptyFlow<Long>()

    var refreshes = 0
        private set

    var gate: CompletableDeferred<Unit>? = null
    var failWith: Throwable? = null

    override suspend fun refresh() {
        refreshes += 1
        gate?.await()
        failWith?.let { throw it }
    }
}

private fun sessionOn(host: String, id: Long) = SessionRow(
    id = id,
    tmuxName = "s$id",
    hostAlias = host,
    projectId = null,
    claudeStatus = "working",
    stuckKind = null,
    currentActivity = null,
    lastActivityAt = id,
)

class HostsViewModelTest {

    /**
     * The hub answers `ORDER BY (alias='local') DESC, alias ASC`
     * (`store/hosts_accounts.rs`), but a `host:added` frame *appends* to the
     * snapshot, so after one event the hub's order is gone. The order is
     * therefore the app's own, and it is the hub's — the machine the hub itself
     * runs on first, then alphabetically.
     */
    @Test
    fun hosts_are_ordered_with_the_hubs_own_machine_first_then_alphabetically() = runTest {
        val fleet = FakeFleetForHosts(
            hostRows = listOf(HostRow("zebra"), HostRow("willow"), HostRow("local"), HostRow("alpha")),
        )
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        assertEquals(
            listOf("local", "alpha", "willow", "zebra"),
            vm.state.value.hosts.map { it.alias },
        )
    }

    @Test
    fun a_host_carries_its_session_count() = runTest {
        val fleet = FakeFleetForHosts(
            hostRows = listOf(HostRow("box"), HostRow("pine")),
            sessionRows = listOf(sessionOn("box", 1), sessionOn("box", 2), sessionOn("pine", 3)),
        )
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        assertEquals(mapOf("box" to 2, "pine" to 1), vm.state.value.hosts.associate { it.alias to it.sessions })
    }

    @Test
    fun a_host_with_no_sessions_is_still_listed() = runTest {
        val fleet = FakeFleetForHosts(hostRows = listOf(HostRow("idle")))
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        assertEquals(listOf("idle"), vm.state.value.hosts.map { it.alias })
        assertEquals(0, vm.state.value.hosts.single().sessions)
    }

    /**
     * `list_hosts` returns hidden hosts too — `hidden` is the desktop sidebar's
     * own "do not show me this", and the desktop's own Hosts view is where you
     * toggle it, so it lists them as well. The app cannot unhide one
     * (`hide_host` is not a tool a client token may call), so dropping a hidden
     * host that still has sessions would leave those sessions on the Sessions
     * screen with nowhere to look the machine up.
     */
    @Test
    fun a_hidden_host_is_listed_and_marked_rather_than_dropped() = runTest {
        val fleet = FakeFleetForHosts(
            hostRows = listOf(HostRow("shy", hidden = true), HostRow("box")),
            sessionRows = listOf(sessionOn("shy", 1)),
        )
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        val shy = vm.state.value.hosts.single { it.alias == "shy" }
        assertTrue(shy.hidden)
        assertEquals(1, shy.sessions)
    }

    @Test
    fun the_versions_the_hub_reported_are_shown_and_a_missing_one_is_not_invented() = runTest {
        val fleet = FakeFleetForHosts(
            hostRows = listOf(
                HostRow("known", reachable = true, claudeVersion = "2.1.144", tmuxVersion = "3.6a"),
                HostRow("never-probed"),
            ),
        )
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        val known = vm.state.value.hosts.single { it.alias == "known" }
        assertEquals("2.1.144", known.claudeVersion)
        assertEquals("3.6a", known.tmuxVersion)
        assertTrue(known.reachable)

        val unprobed = vm.state.value.hosts.single { it.alias == "never-probed" }
        assertNull(unprobed.claudeVersion, "an unprobed host has no version, and '' is not one")
        assertNull(unprobed.tmuxVersion)
        assertFalse(unprobed.reachable)
    }

    /**
     * The hub reaches an agent host over its own transport rather than SSH, and
     * that is worth a word on the screen: "unreachable" means something
     * different for each.
     */
    @Test
    fun a_hosts_transport_survives_to_the_screen() = runTest {
        val fleet = FakeFleetForHosts(
            hostRows = listOf(HostRow("phone-agent", transport = "agent"), HostRow("box")),
        )
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        assertEquals("agent", vm.state.value.hosts.single { it.alias == "phone-agent" }.transport)
        assertEquals("ssh", vm.state.value.hosts.single { it.alias == "box" }.transport)
    }

    // -----------------------------------------------------------------------
    // Refresh
    // -----------------------------------------------------------------------

    @Test
    fun a_refresh_that_fails_keeps_the_rows_and_says_why() = runTest {
        val fleet = FakeFleetForHosts(hostRows = listOf(HostRow("box")))
        fleet.failWith = HubError.Tool("E_TIMEOUT", "list_hosts timed out")
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        vm.refresh()
        runCurrent()

        val error = assertNotNull(vm.state.value.error)
        assertTrue(
            "E_TIMEOUT" in error.details.orEmpty(),
            "the hub's code is what an operator greps for: ${error.details}",
        )
        assertEquals(listOf("box"), vm.state.value.hosts.map { it.alias }, "the last picture is still the best one")
        assertFalse(vm.state.value.refreshing)
    }


    /**
     * Review N-B1: the banner can be put away.
     *
     * It could not be, on three of the five screens. A failed refresh left its
     * sentence on screen until the next refresh succeeded, and on a hub that is
     * down that is never — an error a person has read and cannot dismiss is how
     * people learn to stop reading the banner.
     */
    @Test
    fun a_failure_can_be_dismissed_without_the_rows_going_with_it() = runTest {
        val fleet = FakeFleetForHosts(hostRows = listOf(HostRow("box")))
        fleet.failWith = HubError.Tool("E_TIMEOUT", "list_hosts timed out")
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertNotNull(vm.state.value.error)

        vm.dismissError()
        // The screen's state is assembled from `local` and the fleet flows by a
        // `combine(...).stateIn(...)`, so the new value lands on the next turn
        // of the test dispatcher rather than inside `dismissError`.
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(listOf("box"), vm.state.value.hosts.map { it.alias }, "the rows stay")
    }

    @Test
    fun the_spinner_runs_for_as_long_as_the_refresh_does() = runTest {
        val fleet = FakeFleetForHosts(hostRows = listOf(HostRow("box")))
        fleet.gate = CompletableDeferred()
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()

        vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.refreshing)

        fleet.gate?.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.refreshing)
        assertNull(vm.state.value.error)
        assertEquals(1, fleet.refreshes)
    }

    /** A hub with no hosts at all is a state, not a blank screen. */
    @Test
    fun an_empty_fleet_is_reported_as_empty() = runTest {
        val vm = HostsViewModel(FakeFleetForHosts(), backgroundScope)
        runCurrent()

        assertTrue(vm.state.value.isEmpty)
    }
}
