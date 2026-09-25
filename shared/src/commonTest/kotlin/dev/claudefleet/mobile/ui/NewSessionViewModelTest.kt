@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.PastLink
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StartFailure
import dev.claudefleet.mobile.model.StartSkip
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

    // ---- ticket mode (M8.4): Start here from the Tickets sheet ----

    private fun ticketVm(
        fleet: FleetState,
        work: FakeWorkActions,
        scope: kotlinx.coroutines.CoroutineScope,
        opened: MutableList<Long>,
        canWrite: Boolean = true,
    ) = NewSessionViewModel(
        fleet, FakeCreate(), scope, canWrite, initialHost = "pine", onCreated = { opened += it },
        ticketKey = "PAY-9", workActions = work,
    )

    /** No project picked is the default: the hub uses the project that last worked on PAY-*. */
    @Test
    fun ticket_mode_starts_work_with_the_hubs_default_project() = runTest {
        val work = FakeWorkActions()
        val opened = mutableListOf<Long>()
        val vm = ticketVm(WorkFleet(hostRows = listOf(PINE, BOX), projectRows = listOf(REPO)), work, backgroundScope, opened)
        runCurrent()

        assertEquals("PAY-9", vm.state.value.ticketKey)
        assertTrue(vm.state.value.canCreate, "no project needed")
        vm.create()
        runCurrent()

        assertEquals(listOf("start PAY-9 pine -"), work.calls)
        assertEquals(listOf(99L), opened, "the started session opens, exactly as a created one does")
    }

    @Test
    fun ticket_mode_passes_a_project_the_person_picked() = runTest {
        val work = FakeWorkActions()
        val vm = ticketVm(WorkFleet(hostRows = listOf(PINE), projectRows = listOf(REPO)), work, backgroundScope, mutableListOf())
        vm.selectProject(3)
        runCurrent()
        vm.create()
        runCurrent()

        assertEquals(listOf("start PAY-9 pine 3"), work.calls)
    }

    /** `E_EXISTS` is Jump: the phone opens the live session and never makes a second one. */
    @Test
    fun a_start_the_hub_refuses_as_existing_opens_that_session() = runTest {
        val work = FakeWorkActions().apply {
            fail = HubError.Tool("E_EXISTS", "PAY-9 already has a live session; jump to it", buildJsonObject { put("session_id", 41) })
        }
        val opened = mutableListOf<Long>()
        val vm = ticketVm(WorkFleet(hostRows = listOf(PINE)), work, backgroundScope, opened)
        runCurrent()
        vm.create()
        runCurrent()

        assertEquals(listOf(41L), opened)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.creating)
    }

    /** `E_AMBIGUOUS`: the hub cannot pick a project, and its candidates become the list. */
    @Test
    fun an_ambiguous_start_offers_the_hubs_candidates() = runTest {
        val candidates = buildJsonObject {
            putJsonArray("candidates") {
                add(buildJsonObject { put("id", 4); put("owner", "me"); put("repo", "other") })
            }
        }
        val work = FakeWorkActions().apply { fail = HubError.Tool("E_AMBIGUOUS", "no project has worked on PAY-* yet; pick one", candidates) }
        val vm = ticketVm(WorkFleet(hostRows = listOf(PINE), projectRows = listOf(REPO, OTHER)), work, backgroundScope, mutableListOf())
        runCurrent()
        vm.create()
        runCurrent()

        assertEquals(listOf(4L), vm.state.value.projects.map { it.id })
        assertEquals("Pick one", vm.state.value.error?.title)
        // Not creatable until a project is picked: Create again would send the
        // same request and get the same refusal.
        assertFalse(vm.state.value.canCreate, "the hub cannot pick, so the person must")
        vm.create()
        runCurrent()
        assertEquals(listOf("start PAY-9 pine -"), work.calls, "no second identical request")

        work.fail = null
        vm.selectProject(4)
        runCurrent()
        assertTrue(vm.state.value.canCreate, "a candidate picked: the form unlocks")
        vm.create()
        runCurrent()
        assertEquals(listOf("start PAY-9 pine -", "start PAY-9 pine 4"), work.calls)
    }

    @Test
    fun ticket_mode_is_never_offered_without_work_link_start_or_to_a_readonly_token() = runTest {
        val noStart = WorkFleet(hostRows = listOf(PINE), caps = HubCapabilities.of(ToolCatalog(setOf("work"))))
        val work = FakeWorkActions()
        val hidden = ticketVm(noStart, work, backgroundScope, mutableListOf())
        val readonly = ticketVm(WorkFleet(hostRows = listOf(PINE)), work, backgroundScope, mutableListOf(), canWrite = false)
        runCurrent()

        assertFalse(hidden.state.value.canCreate)
        assertFalse(readonly.state.value.canCreate)
        hidden.create(); readonly.create()
        runCurrent()
        assertEquals(emptyList(), work.calls)
    }

    // ---- multi-repo start (claude-fleet M9.6): Also start in… ----

    private val API = ProjectRow(4, owner = "me", repo = "api", lastSessionAt = 50)
    private val WEB = ProjectRow(5, owner = "me", repo = "web", lastSessionAt = 40)
    private val MULTI = HubCapabilities.of(
        ToolCatalog(setOf("work", "work_link"), params = mapOf("work_link" to setOf("action", "key", "project_ids"))),
    )

    private fun multiFleet(sessions: List<SessionRow> = emptyList()) =
        WorkFleet(sessions, caps = MULTI, hostRows = listOf(PINE), projectRows = listOf(REPO, API, WEB))

    private fun multiVm(
        fleet: WorkFleet,
        work: FakeWorkActions,
        scope: kotlinx.coroutines.CoroutineScope,
        opened: MutableList<Long>,
        notes: MutableList<String> = mutableListOf(),
        canWrite: Boolean = true,
    ) = NewSessionViewModel(
        fleet, FakeCreate(), scope, canWrite, initialHost = "pine", onCreated = { opened += it },
        ticketKey = "PAY-9", workActions = work, onNote = { notes += it },
    )

    /** The repositories the key ran in before, newest first, never the one picked, only ones the fleet lists. */
    @Test
    fun siblings_are_where_the_ticket_ran_before_newest_first() = runTest {
        val work = FakeWorkActions().apply {
            pastLinksAnswer = listOf(
                PastLink(snapProjectId = 4, createdAt = 10, endedAt = 20),
                PastLink(snapProjectId = 3, createdAt = 30, endedAt = 40),
                PastLink(snapProjectId = 99, createdAt = 50, endedAt = 60),
            )
        }
        val live = SessionRow(id = 7, projectId = 5, lastActivityAt = 100, work = WorkSummary(linkId = 1, key = "pay-9"))
        val vm = multiVm(multiFleet(listOf(live)), work, backgroundScope, mutableListOf())
        runCurrent()
        assertEquals(listOf("PAY-9"), work.pastLinkCalls)
        assertEquals(emptyList(), vm.state.value.siblings, "no project picked: nothing to be a sibling of")

        vm.selectProject(3)
        runCurrent()
        assertEquals(listOf(5L, 4L), vm.state.value.siblings.map { it.id }, "web (live, newest), then api; never repo itself or an unknown 99")
    }

    @Test
    fun a_multi_repo_start_opens_the_picked_repos_session_and_notes_the_rest() = runTest {
        val work = FakeWorkActions().apply {
            pastLinksAnswer = listOf(PastLink(snapProjectId = 4, createdAt = 1), PastLink(snapProjectId = 5, createdAt = 2))
            multiAnswer = MultiStart(
                key = "PAY-9",
                started = listOf(SessionRow(id = 21, projectId = 4), SessionRow(id = 20, projectId = 3)),
                skipped = listOf(StartSkip(projectId = 5, sessionId = 8, reason = "PAY-9 runs there")),
            )
        }
        val opened = mutableListOf<Long>()
        val notes = mutableListOf<String>()
        val vm = multiVm(multiFleet(), work, backgroundScope, opened, notes)
        runCurrent()
        vm.selectProject(3)
        vm.toggleSibling(4)
        vm.toggleSibling(5)
        runCurrent()
        assertEquals(listOf(4L, 5L), vm.state.value.siblingsTicked)

        vm.create()
        runCurrent()
        assertEquals(listOf("start_multi PAY-9 pine 3,4,5"), work.calls)
        assertEquals(listOf(20L), opened, "the picked repository's session, not merely the first started")
        assertEquals(listOf("Started 2; me/web: already running"), notes)
    }

    /** Nothing started but the key runs in one of them: that session opens, as a single start's E_EXISTS does. */
    @Test
    fun a_multi_repo_start_that_started_nothing_jumps_or_says_why() = runTest {
        val work = FakeWorkActions().apply {
            pastLinksAnswer = listOf(PastLink(snapProjectId = 4, createdAt = 1))
            multiAnswer = MultiStart(key = "PAY-9", skipped = listOf(StartSkip(projectId = 3, sessionId = 8)), failed = listOf(StartFailure(4, "E_SSH", "pine is down")))
        }
        val opened = mutableListOf<Long>()
        val vm = multiVm(multiFleet(), work, backgroundScope, opened)
        runCurrent()
        vm.selectProject(3)
        vm.toggleSibling(4)
        runCurrent()
        vm.create()
        runCurrent()
        assertEquals(listOf(8L), opened)

        work.multiAnswer = MultiStart(key = "PAY-9", failed = listOf(StartFailure(3, "E_SSH", "pine is down"), StartFailure(4, "E_SSH", "pine is down")))
        vm.create()
        runCurrent()
        assertEquals(listOf(8L), opened, "nothing more opened")
        assertEquals("Nothing started", vm.state.value.error?.title)
        assertFalse(vm.state.value.creating)
    }

    /** A tick for a repository no longer on offer (another project picked, so it is now the primary) never reaches the hub. */
    @Test
    fun a_stale_tick_is_not_started() = runTest {
        val work = FakeWorkActions().apply {
            pastLinksAnswer = listOf(PastLink(snapProjectId = 4, createdAt = 1), PastLink(snapProjectId = 3, createdAt = 2))
        }
        val vm = multiVm(multiFleet(), work, backgroundScope, mutableListOf())
        runCurrent()
        vm.selectProject(3)
        vm.toggleSibling(4)
        vm.selectProject(4)
        runCurrent()
        assertEquals(emptyList(), vm.state.value.siblingsTicked, "4 is the primary now; 3 was never ticked")
        vm.create()
        runCurrent()
        assertEquals(listOf("start PAY-9 pine 4"), work.calls, "a plain start")
    }

    /** A hub whose `work_link` does not name `project_ids` would ignore it and start one: no siblings offered. A readonly form reads nothing. */
    @Test
    fun siblings_need_a_hub_that_takes_project_ids_and_a_write_token() = runTest {
        val work = FakeWorkActions().apply { pastLinksAnswer = listOf(PastLink(snapProjectId = 4, createdAt = 1)) }
        val old = WorkFleet(hostRows = listOf(PINE), projectRows = listOf(REPO, API))
        val vm = multiVm(old, work, backgroundScope, mutableListOf())
        runCurrent()
        vm.selectProject(3)
        runCurrent()
        assertEquals(emptyList(), vm.state.value.siblings)

        val readonlyWork = FakeWorkActions()
        multiVm(multiFleet(), readonlyWork, backgroundScope, mutableListOf(), canWrite = false)
        runCurrent()
        assertEquals(emptyList(), readonlyWork.pastLinkCalls)
    }

    @Test
    fun the_note_names_what_was_left_out_or_is_null() {
        val label = { id: Long -> "p$id" }
        assertEquals(null, multiStartNote(MultiStart(started = listOf(SessionRow(id = 1))), label))
        assertEquals(
            "Started 1; p5: already running; p6: pine is down",
            multiStartNote(MultiStart(started = listOf(SessionRow(id = 1)), skipped = listOf(StartSkip(5)), failed = listOf(StartFailure(6, message = "pine is down"))), label),
        )
    }
}
