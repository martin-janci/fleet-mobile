@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.NewBgSessionResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.ui.kit.pulseScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class WizardFleet(
    hostRows: List<HostRow>,
    projectRows: List<ProjectRow> = emptyList(),
    sessionRows: List<SessionRow> = emptyList(),
) : FleetState {
    override val sessions = MutableStateFlow(sessionRows)
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow(projectRows)
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = emptyFlow<Long>()
    override suspend fun refresh() {}
    override val capabilities = MutableStateFlow(HubCapabilities(tools = setOf(HubCapabilities.NEW_BG_SESSION)))
}

private object NoCreate : NewSessionActions {
    override suspend fun newSession(request: NewSessionRequest): SessionRow = SessionRow(id = 1, hostAlias = request.hostAlias)
    override suspend fun newBackground(hostAlias: String, name: String, prompt: String) =
        NewBgSessionResult(claudeSessionId = "c", session = null)
}

private val PINE = HostRow("pine", reachable = true)
private val BOX = HostRow("box", reachable = true)
private val DOWN = HostRow("down", reachable = false)
private val REPO = ProjectRow(3, owner = "me", repo = "repo", lastSessionAt = 100)

/**
 * The New session wizard (redesign 14.6): the step gates and words. The
 * rules themselves are [NewSessionViewModel]'s and are tested there; this
 * pins that each step asks for exactly its own part, and that Start stays
 * off on an invalid branch.
 */
class NewSessionWizardTest {

    private fun vm(fleet: FleetState, scope: CoroutineScope, ticketKey: String? = null) =
        NewSessionViewModel(fleet, NoCreate, scope, canWrite = true, initialHost = null, onCreated = {}, ticketKey = ticketKey)

    @Test
    fun the_steps_are_where_project_review() {
        assertEquals(listOf("Where", "Project", "Review"), WizardStep.entries.map { it.title })
        assertEquals(WizardStep.Project, WizardStep.Where.next)
        assertNull(WizardStep.Review.next, "Review ends in Create, not another step")
        assertNull(WizardStep.Where.previous, "the first step's back is Cancel")
    }

    @Test
    fun the_heading_names_the_step_and_the_host() {
        val s = NewSessionUiState(host = "mercury")
        assertEquals("Step 1 of 3 · Where", stepHeading(WizardStep.Where, s))
        assertEquals("Step 2 of 3 · Project on mercury", stepHeading(WizardStep.Project, s))
        assertEquals("Step 3 of 3 · Review", stepHeading(WizardStep.Review, s))
    }

    @Test
    fun where_waits_for_a_reachable_host_and_says_so() = runTest {
        val vm = vm(WizardFleet(listOf(PINE, BOX, DOWN)), backgroundScope)
        runCurrent()
        assertEquals("Pick a host.", nextBlocker(WizardStep.Where, vm.state.value))

        vm.selectHost("pine")
        runCurrent()
        assertNull(nextBlocker(WizardStep.Where, vm.state.value))

        val none = vm(WizardFleet(listOf(DOWN)), backgroundScope)
        runCurrent()
        assertEquals("No host is reachable right now.", nextBlocker(WizardStep.Where, none.state.value))
    }

    @Test
    fun project_waits_for_a_project() = runTest {
        val vm = vm(WizardFleet(listOf(PINE), listOf(REPO)), backgroundScope)
        runCurrent()
        assertEquals("Pick a project.", nextBlocker(WizardStep.Project, vm.state.value))

        vm.selectProject(REPO.id)
        runCurrent()
        assertNull(nextBlocker(WizardStep.Project, vm.state.value))
        assertTrue(vm.state.value.canCreate)
    }

    /** The plan's Verify for 14.6: Start stays disabled on an invalid branch. */
    @Test
    fun an_invalid_branch_holds_project_and_keeps_start_off() = runTest {
        val vm = vm(WizardFleet(listOf(PINE), listOf(REPO)), backgroundScope)
        vm.selectProject(REPO.id)
        vm.setNewWorktree(true)
        runCurrent()
        assertEquals("Name the new branch.", nextBlocker(WizardStep.Project, vm.state.value))

        vm.onBranchChange("bad branch name")
        runCurrent()
        assertEquals("A branch name has no spaces.", nextBlocker(WizardStep.Project, vm.state.value))
        assertFalse(vm.state.value.canCreate, "Start stays disabled on an invalid branch")
        assertEquals("A branch name has no spaces.", vm.state.value.missing, "and Review's caption says why")

        vm.onBranchChange("orbit-redesign")
        runCurrent()
        assertNull(nextBlocker(WizardStep.Project, vm.state.value))
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun a_ticket_may_leave_the_project_to_the_hub() = runTest {
        val s = NewSessionUiState(host = "pine", ticketKey = "FLEET-151")
        assertNull(nextBlocker(WizardStep.Project, s))
        assertEquals("Start here", createLabel(s))
        assertEquals(listOf("Worktree", "tmux pane", "Claude Code"), startSteps(s))
    }

    @Test
    fun hosts_carry_their_load_by_status_word() = runTest {
        val sessions = listOf(
            SessionRow(id = 1, hostAlias = "pine", claudeStatus = "blocked"),
            SessionRow(id = 2, hostAlias = "pine", claudeStatus = "working"),
            SessionRow(id = 3, hostAlias = "pine", claudeStatus = "working"),
            SessionRow(id = 4, hostAlias = "pine", claudeStatus = "idle"),
            SessionRow(id = 5, hostAlias = "pine", claudeStatus = "working", lostAt = 10),
            SessionRow(id = 6, hostAlias = "box", claudeStatus = "working"),
        )
        val vm = vm(WizardFleet(listOf(PINE, BOX, DOWN), sessionRows = sessions), backgroundScope)
        runCurrent()
        val byAlias = vm.state.value.hosts.associateBy { it.alias }

        assertEquals("1 needs you · 2 working · 1 idle", hostLoad(byAlias.getValue("pine")), "a lost row is not load")
        assertEquals("1 working", hostLoad(byAlias.getValue("box")))
        assertEquals("Signal lost · cannot start there now", hostLoad(byAlias.getValue("down")))
        assertEquals("No sessions", hostLoad(HostChoice("empty", reachable = true)))
    }

    @Test
    fun create_says_what_it_will_start() {
        assertEquals("Create session", createLabel(NewSessionUiState()))
        assertEquals("Creating…", createLabel(NewSessionUiState(creating = true)))
        assertEquals("Start in 3 projects…", createLabel(NewSessionUiState(ticketKey = "K-1", alsoInIds = listOf(4, 5))))
    }

    /** Only the steps the hub really runs: no worktree step for the project's own checkout. */
    @Test
    fun the_pulse_lists_the_real_steps() {
        assertEquals(listOf("tmux pane", "Claude Code"), startSteps(NewSessionUiState()))
        assertEquals(listOf("Worktree", "tmux pane", "Claude Code"), startSteps(NewSessionUiState(newWorktree = true)))
        assertEquals("Starting orbit-redesign", startingTitle(NewSessionUiState(newWorktree = true, branch = "orbit-redesign"), background = false))
        assertEquals("Starting Orbit", startingTitle(NewSessionUiState(friendlyName = "Orbit", newWorktree = true, branch = "x"), background = false))
        assertEquals("Starting K-1 in 2 projects", startingTitle(NewSessionUiState(ticketKey = "K-1", alsoInIds = listOf(9)), background = false))
        assertEquals("Starting a background agent", startingTitle(NewSessionUiState(), background = true))
    }

    /** The manual's Pulse: 1 → 1.4 at 30 % of 1.1 s → 1 at 60 %, then rest. */
    @Test
    fun the_pulse_swells_and_rests_on_the_manuals_curve() {
        assertEquals(1f, pulseScale(0f), 0.001f)
        assertEquals(1.4f, pulseScale(330f), 0.001f)
        assertEquals(1f, pulseScale(660f), 0.001f)
        assertEquals(1f, pulseScale(900f), 0.001f)
        assertEquals(pulseScale(330f), pulseScale(330f + 1100f), 0.001f)
        assertEquals(pulseScale(920f), pulseScale(-180f), 0.001f)
    }
}
