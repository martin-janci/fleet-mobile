package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.RestoreOutcome
import dev.claudefleet.mobile.model.RestorePlanEntry
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class HostFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(listOf(SessionRow(id = 1, tmuxName = "a", hostAlias = "pine")))
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Hosts : HostActions {
    val calls = mutableListOf<String>()
    var stillLost = true
    override suspend fun probe(alias: String): HostRow {
        calls += "probe"
        return HostRow(alias = alias, reachable = true)
    }
    override suspend fun restorePlan(alias: String): RestoreReport {
        calls += "plan"
        return RestoreReport(
            plan = if (stillLost) {
                listOf(RestorePlanEntry(sessionId = 5, tmuxName = "lost-one", action = "restore"), RestorePlanEntry(sessionId = 6, action = "skip", reason = "controller"))
            } else {
                emptyList()
            },
        )
    }
    override suspend fun restore(alias: String): RestoreReport {
        calls += "restore"
        stillLost = false
        return RestoreReport(dryRun = false, results = listOf(RestoreOutcome(sessionId = 5, tmuxName = "lost-one", ok = true)))
    }
    override suspend fun discover(alias: String): List<LostCandidate> {
        calls += "discover"
        return listOf(
            LostCandidate(claudeSessionId = "c1", cwd = "/w/a", projectId = 1, resumable = true),
            LostCandidate(claudeSessionId = "c2", cwd = "/w/b", projectId = 1, resumable = false),
            LostCandidate(claudeSessionId = "c3", cwd = "/w/c", projectId = 1, resumable = true, existingSessionId = 5),
            LostCandidate(claudeSessionId = "c4", cwd = "/tmp", projectId = null, resumable = true),
        )
    }
    override suspend fun resume(alias: String, candidate: LostCandidate): SessionRow {
        calls += "resume:${candidate.claudeSessionId}"
        return SessionRow(id = 42, tmuxName = "resumed")
    }
}

private val ALL_HOST = setOf(HubCapabilities.PROBE_HOST, HubCapabilities.RESTORE_HOST_SESSIONS, HubCapabilities.DISCOVER_LOST_SESSIONS)

class HostDetailViewModelTest {

    @Test
    fun opening_a_host_plans_a_restore_and_looks_for_untracked_conversations() = runTest {
        val hosts = Hosts()
        val vm = HostDetailViewModel(HostFleet(ALL_HOST), hosts, backgroundScope, canWrite = true)

        vm.open("pine").join()
        runCurrent()

        val s = vm.state.value
        assertEquals(listOf("plan", "discover"), hosts.calls)
        assertEquals(1, s.restorable)
        assertEquals(1, s.sessionCount)
        // Only what can really be resumed: resumable, in a project, not a fleet row's.
        assertEquals(listOf("c1"), s.resumable.map { it.claudeSessionId })
    }

    @Test
    fun a_readonly_pairing_reads_but_is_offered_no_restore() = runTest {
        val hosts = Hosts()
        val vm = HostDetailViewModel(HostFleet(ALL_HOST), hosts, backgroundScope, canWrite = false)
        vm.open("pine").join()
        runCurrent()

        assertFalse(vm.state.value.canRestore)
        assertEquals(listOf("discover"), hosts.calls)
        vm.restore().join()
        vm.resume(LostCandidate(claudeSessionId = "c1", projectId = 1, resumable = true)) {}.join()
        assertEquals(listOf("discover"), hosts.calls)
    }

    @Test
    fun restore_runs_and_then_looks_again() = runTest {
        val hosts = Hosts()
        val vm = HostDetailViewModel(HostFleet(ALL_HOST), hosts, backgroundScope, canWrite = true)
        vm.open("pine").join()
        runCurrent()

        vm.restore().join()
        runCurrent()

        assertEquals(listOf("plan", "discover", "restore", "plan", "discover"), hosts.calls)
        assertEquals(0, vm.state.value.restorable)
        assertTrue(vm.state.value.restored!!.results.single().ok)
    }

    @Test
    fun resuming_a_conversation_opens_the_new_session() = runTest {
        val hosts = Hosts()
        val vm = HostDetailViewModel(HostFleet(ALL_HOST), hosts, backgroundScope, canWrite = true)
        vm.open("pine").join()
        runCurrent()
        var opened: Long? = null

        vm.resume(vm.state.value.resumable.single()) { opened = it }.join()
        runCurrent()

        assertEquals("resume:c1", hosts.calls.last())
        assertEquals(42L, opened)
        assertNull(vm.state.value.resuming)
    }

    @Test
    fun close_forgets_the_host() = runTest {
        val vm = HostDetailViewModel(HostFleet(ALL_HOST), Hosts(), backgroundScope, canWrite = true)
        vm.open("pine").join()
        vm.close()
        runCurrent()
        assertNull(vm.state.value.alias)
    }
}
