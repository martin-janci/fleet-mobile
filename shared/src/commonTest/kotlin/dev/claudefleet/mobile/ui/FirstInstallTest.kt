package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AgentInstallActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AgentInstall
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.ui.kit.StepState
import dev.claudefleet.mobile.ui.kit.sonarRing
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val INSTALL_TOOLS = setOf(HubCapabilities.INSTALL_AGENT, HubCapabilities.AGENT_INSTALLS)

private class InstallFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(
        listOf(
            HostRow(alias = "pine", reachable = true, tmuxVersion = "3.4"),
            HostRow(alias = "oak", reachable = true, tmuxVersion = "3.4", transport = "agent"),
            HostRow(alias = "elm", reachable = false),
        ),
    )
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

/** The hub's job, walked one step per read: target, download, start, connect, done. */
private class Installs : AgentInstallActions {
    val installs = mutableListOf<String>()
    var reads = 0
    private val walk = listOf("download", "start", "connect", "done")

    override suspend fun install(alias: String): AgentInstall {
        installs += alias
        return AgentInstall(id = 9, hostAlias = alias, version = "0.9.3")
    }

    override suspend fun jobs(alias: String): List<AgentInstall> {
        val step = walk[reads.coerceAtMost(walk.lastIndex)]
        reads++
        return listOf(
            AgentInstall(id = 9, hostAlias = alias, version = "0.9.3", step = step, state = if (step == "done") "done" else "running"),
        )
    }
}

class FirstInstallTest {

    @Test
    fun the_welcome_shows_once_on_the_new_layout_and_never_over_a_sign_out() {
        assertTrue(showWelcome(PhoneLayout.New, welcomed = false, signedOut = false))
        assertFalse(showWelcome(PhoneLayout.New, welcomed = true, signedOut = false))
        assertFalse(showWelcome(PhoneLayout.New, welcomed = false, signedOut = true))
        assertFalse(showWelcome(PhoneLayout.Classic, welcomed = false, signedOut = false))
    }

    @Test
    fun the_no_hub_steps_name_the_command_and_the_way_back() {
        assertTrue(HUB_SERVE_COMMAND in NO_HUB_STEPS_TEXT)
        assertTrue("Add a phone" in NO_HUB_STEPS_TEXT)
        assertEquals(3, WELCOME_PROMISES.size)
    }

    @Test
    fun pulse_while_the_steps_run_and_sonar_while_the_hub_waits_for_the_heartbeat() {
        val job = AgentInstall(id = 1, hostAlias = "pine")
        assertEquals(InstallPhase.Running, installPhase(job))
        assertEquals(InstallPhase.Running, installPhase(job.copy(step = "start")))
        assertEquals(InstallPhase.Heartbeat, installPhase(job.copy(step = "connect")))
        assertEquals(InstallPhase.Done, installPhase(job.copy(step = "done", state = "done")))
        assertEquals(InstallPhase.Failed, installPhase(job.copy(step = "connect", state = "failed")))
    }

    @Test
    fun the_checklist_follows_the_job_and_marks_the_step_it_failed_on() {
        val running = installSteps(AgentInstall(id = 1, hostAlias = "pine", version = "0.9.3", step = "download"))
        assertEquals(listOf(StepState.Done, StepState.Running, StepState.Pending, StepState.Pending), running.map { it.state })
        assertEquals("Copying fleet-agent 0.9.3", running[1].label)

        val failed = installSteps(AgentInstall(id = 1, hostAlias = "pine", step = "connect", state = "failed"))
        assertEquals("failed", failed[3].detail)
        assertNull(failed[2].detail)

        val done = installSteps(AgentInstall(id = 1, hostAlias = "pine", step = "done", state = "done"))
        assertTrue(done.all { it.state == StepState.Done })
        assertEquals("First heartbeat", done.last().label)
    }

    @Test
    fun only_a_reachable_ssh_host_on_a_hub_that_offers_the_job_can_install() {
        val pine = HostRow(alias = "pine", reachable = true)
        assertTrue(canInstallOn(pine, canWrite = true, hubOffers = true))
        assertFalse(canInstallOn(pine, canWrite = false, hubOffers = true))
        assertFalse(canInstallOn(pine, canWrite = true, hubOffers = false))
        assertFalse(canInstallOn(pine.copy(reachable = false), canWrite = true, hubOffers = true))
        assertFalse(canInstallOn(pine.copy(transport = "agent"), canWrite = true, hubOffers = true))
        assertFalse(canInstallOn(null, canWrite = true, hubOffers = true))
    }

    @Test
    fun opening_the_review_installs_nothing_until_the_tap() = runTest {
        val actions = Installs()
        val vm = AgentInstallViewModel(InstallFleet(INSTALL_TOOLS), actions, backgroundScope, canWrite = true, pollMs = 100)

        vm.open("pine")
        runCurrent()
        val review = vm.state.value
        assertTrue(review.reviewing)
        assertTrue(review.canInstall)
        assertEquals("0.9.3", review.hubVersion)
        assertEquals(emptyList<String>(), actions.installs)

        vm.install().join()
        runCurrent()
        assertEquals(listOf("pine"), actions.installs)
        assertTrue(vm.state.value.installing)
    }

    @Test
    fun the_job_is_followed_to_the_first_heartbeat_and_then_stops_being_read() = runTest {
        val actions = Installs()
        val vm = AgentInstallViewModel(InstallFleet(INSTALL_TOOLS), actions, backgroundScope, canWrite = true, pollMs = 100)
        vm.open("pine")
        vm.install().join()

        repeat(3) { advanceTimeBy(101); runCurrent() }
        assertEquals(InstallPhase.Heartbeat, installPhase(assertNotNull(vm.state.value.job)))

        advanceTimeBy(101)
        runCurrent()
        assertEquals(InstallPhase.Done, installPhase(assertNotNull(vm.state.value.job)))

        val reads = actions.reads
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(reads, actions.reads, "a finished job is not read again")
    }

    @Test
    fun a_hub_that_does_not_offer_the_job_is_never_asked() = runTest {
        val actions = Installs()
        val vm = AgentInstallViewModel(InstallFleet(setOf(HubCapabilities.AGENT_INSTALLS)), actions, backgroundScope, canWrite = true)
        vm.open("pine")
        runCurrent()
        assertFalse(vm.state.value.canInstall)
        vm.install().join()
        assertEquals(emptyList<String>(), actions.installs)
    }

    @Test
    fun a_host_already_on_the_agent_or_not_answering_is_not_installed_on() = runTest {
        val actions = Installs()
        val vm = AgentInstallViewModel(InstallFleet(INSTALL_TOOLS), actions, backgroundScope, canWrite = true)
        for (alias in listOf("oak", "elm")) {
            vm.open(alias)
            runCurrent()
            assertFalse(vm.state.value.canInstall, alias)
            vm.install().join()
        }
        assertEquals(emptyList<String>(), actions.installs)
    }

    @Test
    fun leaving_stops_reading_the_job() = runTest {
        val actions = Installs()
        val vm = AgentInstallViewModel(InstallFleet(INSTALL_TOOLS), actions, backgroundScope, canWrite = true, pollMs = 100)
        vm.open("pine")
        vm.install().join()
        vm.close()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, actions.reads)
        assertNull(vm.state.value.alias)
    }

    @Test
    fun sonar_rings_are_staggered_by_a_third_and_wrap() {
        assertEquals(0f, sonarRing(0f, 0))
        assertTrue(kotlin.math.abs(sonarRing(0f, 1) - 1f / 3f) < 1e-6f)
        assertTrue(kotlin.math.abs(sonarRing(0.9f, 1) - (0.9f + 1f / 3f - 1f)) < 1e-5f)
    }
}
