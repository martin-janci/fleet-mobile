@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeFleetForNew(
    hostRows: List<HostRow> = emptyList(),
    projectRows: List<ProjectRow> = emptyList(),
) : FleetState {
    override val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow(projectRows)
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = emptyFlow<Long>()
    override suspend fun refresh() {}
}

private class FakeCreate : NewSessionActions {
    val requests = mutableListOf<NewSessionRequest>()
    var gate: CompletableDeferred<Unit>? = null
    var failWith: Throwable? = null
    var nextId = 41L

    override suspend fun newSession(request: NewSessionRequest): SessionRow {
        requests += request
        gate?.await()
        failWith?.let { throw it }
        return SessionRow(id = nextId, tmuxName = "dev-me-repo", hostAlias = request.hostAlias)
    }
}

private val PINE = HostRow("pine", reachable = true)
private val BOX = HostRow("box", reachable = true)
private val DOWN = HostRow("down", reachable = false)
private val REPO = ProjectRow(3, owner = "me", repo = "repo", lastSessionAt = 100)
private val OTHER = ProjectRow(4, owner = "me", repo = "other", lastSessionAt = 200)

class NewSessionViewModelTest {

    private fun vm(
        fleet: FleetState,
        actions: NewSessionActions = FakeCreate(),
        scope: kotlinx.coroutines.CoroutineScope,
        canWrite: Boolean = true,
        initialHost: String? = null,
        opened: MutableList<Long> = mutableListOf(),
        callScope: kotlinx.coroutines.CoroutineScope = scope,
    ) = NewSessionViewModel(fleet, actions, scope, canWrite, initialHost, onCreated = { opened += it }, callScope = callScope)

    @Test
    fun unreachable_hosts_are_offered_but_cannot_be_chosen() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, DOWN, BOX)), scope = backgroundScope)
        runCurrent()

        assertEquals(listOf("box", "down", "pine"), vm.state.value.hosts.map { it.alias })
        assertFalse(vm.state.value.hosts.single { it.alias == "down" }.reachable)

        vm.selectHost("down")
        runCurrent()
        assertNull(vm.state.value.host, "a host the hub cannot reach cannot take a session")
    }

    /** The hub's own machine first, then alphabetically — the Hosts screen's order. */
    @Test
    fun the_hubs_own_machine_is_listed_first() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, HostRow("local", reachable = true))), scope = backgroundScope)
        runCurrent()

        assertEquals(listOf("local", "pine"), vm.state.value.hosts.map { it.alias })
    }

    /**
     * Hidden is the desktop's "do not show me this", so the form does not offer
     * one — unless the person came from that host's own filtered list, where
     * leaving it out would contradict the screen they just tapped from.
     */
    @Test
    fun a_hidden_host_is_offered_only_when_the_list_was_filtered_to_it() = runTest {
        val shy = HostRow("shy", reachable = true, hidden = true)
        val plain = vm(FakeFleetForNew(listOf(PINE, shy)), scope = backgroundScope)
        val filtered = vm(FakeFleetForNew(listOf(PINE, shy)), scope = backgroundScope, initialHost = "shy")
        runCurrent()

        assertEquals(listOf("pine"), plain.state.value.hosts.map { it.alias })
        assertEquals(listOf("pine", "shy"), filtered.state.value.hosts.map { it.alias })
        assertEquals("shy", filtered.state.value.host)
    }

    @Test
    fun the_list_filter_is_the_first_guess_at_a_host() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, BOX)), scope = backgroundScope, initialHost = "pine")
        runCurrent()

        assertEquals("pine", vm.state.value.host)
    }

    @Test
    fun an_unreachable_filter_host_is_not_guessed() = runTest {
        val vm = vm(FakeFleetForNew(listOf(DOWN, BOX)), scope = backgroundScope, initialHost = "down")
        runCurrent()

        assertNull(vm.state.value.host)
    }

    @Test
    fun the_only_reachable_host_is_chosen_for_you() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, DOWN)), scope = backgroundScope)
        runCurrent()

        assertEquals("pine", vm.state.value.host)
    }

    @Test
    fun with_several_reachable_hosts_nothing_is_guessed() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, BOX)), scope = backgroundScope)
        runCurrent()

        assertNull(vm.state.value.host)
    }

    /**
     * The form can open before the first `list_hosts` has landed — a cold start
     * straight into it is a tap away. The guess is derived, not stored, so it
     * follows the hosts in when they arrive.
     */
    @Test
    fun the_guess_follows_hosts_that_arrive_after_the_form_opened() = runTest {
        val fleet = FakeFleetForNew()
        val vm = vm(fleet, scope = backgroundScope, initialHost = "pine")
        runCurrent()
        assertNull(vm.state.value.host)

        fleet.hosts.value = listOf(PINE, BOX)
        runCurrent()

        assertEquals("pine", vm.state.value.host)
    }

    @Test
    fun a_host_the_person_picked_wins_over_the_guess() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, BOX)), scope = backgroundScope, initialHost = "pine")
        runCurrent()

        vm.selectHost("box")
        runCurrent()

        assertEquals("box", vm.state.value.host)
    }

    @Test
    fun a_chosen_host_that_goes_unreachable_blocks_create() = runTest {
        val fleet = FakeFleetForNew(listOf(PINE, BOX), listOf(REPO))
        val vm = vm(fleet, scope = backgroundScope)
        vm.selectHost("pine")
        vm.selectProject(3)
        runCurrent()
        assertTrue(vm.state.value.canCreate)

        fleet.hosts.value = listOf(PINE.copy(reachable = false), BOX)
        runCurrent()

        assertFalse(vm.state.value.canCreate)
    }

    @Test
    fun projects_are_most_recently_used_first() = runTest {
        val never = ProjectRow(5, owner = "me", repo = "aaa")
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO, never, OTHER)), scope = backgroundScope)
        runCurrent()

        assertEquals(listOf(4L, 3L, 5L), vm.state.value.projects.map { it.id })
        assertEquals("me/other", vm.state.value.projects.first().label)
    }

    @Test
    fun the_project_query_filters_by_name_ignoring_case() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO, OTHER)), scope = backgroundScope)
        vm.onProjectQuery("  OTH ")
        runCurrent()

        assertEquals(listOf(4L), vm.state.value.projects.map { it.id })
    }

    /**
     * Filtering the list does not unpick the project. The screen names the
     * chosen one on its own line, so it is still visible when the list hides it.
     */
    @Test
    fun a_chosen_project_stays_chosen_when_the_query_hides_it() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO, OTHER)), scope = backgroundScope)
        vm.selectProject(3)
        vm.onProjectQuery("other")
        runCurrent()

        assertEquals(3L, vm.state.value.projectId)
        assertEquals("me/repo", vm.state.value.projectLabel)
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun create_needs_a_host_and_a_project() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE, BOX), listOf(REPO)), scope = backgroundScope)
        runCurrent()
        assertFalse(vm.state.value.canCreate, "neither")

        vm.selectHost("pine")
        runCurrent()
        assertFalse(vm.state.value.canCreate, "no project")

        vm.selectProject(3)
        runCurrent()
        assertTrue(vm.state.value.canCreate)
    }

    @Test
    fun a_project_that_disappears_blocks_create() = runTest {
        val fleet = FakeFleetForNew(listOf(PINE), listOf(REPO))
        val vm = vm(fleet, scope = backgroundScope)
        vm.selectProject(3)
        runCurrent()
        assertTrue(vm.state.value.canCreate)

        fleet.projects.value = emptyList()
        runCurrent()

        assertFalse(vm.state.value.canCreate)
    }

    @Test
    fun a_new_worktree_needs_a_branch_without_spaces() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), scope = backgroundScope)
        vm.selectProject(3)
        vm.setNewWorktree(true)
        runCurrent()
        assertFalse(vm.state.value.canCreate, "no branch yet")

        vm.onBranchChange("   ")
        runCurrent()
        assertFalse(vm.state.value.canCreate, "blank")

        vm.onBranchChange("fix login")
        runCurrent()
        assertFalse(vm.state.value.canCreate, "a branch name has no spaces")

        vm.onBranchChange(" fix/login ")
        runCurrent()
        assertTrue(vm.state.value.canCreate, "surrounding spaces are trimmed, not refused")
    }

    /** A readonly pairing has nothing to create with; the hub would refuse it. */
    @Test
    fun a_readonly_pairing_cannot_create() = runTest {
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), scope = backgroundScope, canWrite = false)
        vm.selectProject(3)
        runCurrent()

        assertFalse(vm.state.value.canCreate)
    }

    @Test
    fun create_sends_the_choices_trimmed_and_opens_the_new_session() = runTest {
        val actions = FakeCreate()
        val opened = mutableListOf<Long>()
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), actions, backgroundScope, opened = opened)
        vm.selectProject(3)
        vm.setNewWorktree(true)
        vm.onBranchChange(" feat/phone ")
        vm.onBaseBranchChange(" develop ")
        vm.onFriendlyNameChange("  Phone work ")
        runCurrent()

        vm.create()
        runCurrent()

        assertEquals(
            NewSessionRequest(
                hostAlias = "pine",
                projectId = 3,
                newWorktree = "feat/phone",
                baseBranch = "develop",
                friendlyName = "Phone work",
            ),
            actions.requests.single(),
        )
        assertEquals(listOf(41L), opened)
    }

    /**
     * Left blank, the optional fields are not sent at all: a blank base is the
     * default branch and a blank label is one the hub derives, which is what
     * leaving them out already means.
     */
    @Test
    fun blank_optional_fields_and_a_switched_off_worktree_are_not_sent() = runTest {
        val actions = FakeCreate()
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), actions, backgroundScope)
        vm.selectProject(3)
        // Typed, then switched off: the branch must not ride along regardless.
        vm.setNewWorktree(true)
        vm.onBranchChange("feat/x")
        vm.onBaseBranchChange("develop")
        vm.setNewWorktree(false)
        vm.onFriendlyNameChange("   ")
        runCurrent()

        vm.create()
        runCurrent()

        assertEquals(
            NewSessionRequest(hostAlias = "pine", projectId = 3, newWorktree = null, baseBranch = null, friendlyName = null),
            actions.requests.single(),
        )
    }

    /** A clone can take minutes; a second tap in the meantime must not make a second session. */
    @Test
    fun create_in_flight_locks_the_form_and_a_second_tap_sends_nothing() = runTest {
        val actions = FakeCreate().apply { gate = CompletableDeferred() }
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()

        vm.create()
        runCurrent()
        assertTrue(vm.state.value.creating)
        assertFalse(vm.state.value.canCreate)

        vm.create()
        runCurrent()
        assertEquals(1, actions.requests.size)

        actions.gate!!.complete(Unit)
        runCurrent()
        assertEquals(1, actions.requests.size)
    }

    @Test
    fun a_refusal_keeps_the_form_filled_and_says_why() = runTest {
        val actions = FakeCreate().apply { failWith = HubError.Tool("E_INVALID", "branch name is not valid") }
        val opened = mutableListOf<Long>()
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), actions, backgroundScope, opened = opened)
        vm.selectProject(3)
        vm.setNewWorktree(true)
        vm.onBranchChange("feat/x")
        runCurrent()

        vm.create()
        runCurrent()

        val s = vm.state.value
        assertEquals(emptyList(), opened)
        assertFalse(s.creating)
        assertEquals("branch name is not valid", s.error?.body)
        assertEquals("feat/x", s.branch)
        assertEquals(3L, s.projectId)
        assertTrue(s.canCreate, "the person can fix it and try again")

        vm.dismissError()
        runCurrent()
        assertNull(vm.state.value.error)
    }

    /**
     * The call is bounded above the hub's own limit, so a transport failure
     * here means the connection went, not that the hub gave up — and the hub
     * may well have finished. Saying "failed" would invite a duplicate.
     */
    @Test
    fun a_lost_connection_warns_that_the_session_may_exist_anyway() = runTest {
        val actions = FakeCreate().apply { failWith = HubError.Transport(RuntimeException("reset")) }
        val vm = vm(FakeFleetForNew(listOf(PINE), listOf(REPO)), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()

        vm.create()
        runCurrent()

        val body = vm.state.value.error?.body.orEmpty()
        assertTrue("may still have been created" in body, body)
    }

    /**
     * Leaving the form cancels its scope. The create must not go with it: a
     * dropped request can leave the hub's work half done.
     */
    @Test
    fun leaving_the_form_mid_create_does_not_cancel_the_call() = runTest {
        val actions = FakeCreate().apply { gate = CompletableDeferred() }
        val opened = mutableListOf<Long>()
        val screen = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.Job())
        val vm = vm(
            FakeFleetForNew(listOf(PINE), listOf(REPO)),
            actions,
            scope = screen,
            opened = opened,
            callScope = backgroundScope,
        )
        vm.selectProject(3)
        runCurrent()

        vm.create()
        runCurrent()
        screen.cancel()
        actions.gate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(41L), opened, "the call finished and reported the session")
    }
}
