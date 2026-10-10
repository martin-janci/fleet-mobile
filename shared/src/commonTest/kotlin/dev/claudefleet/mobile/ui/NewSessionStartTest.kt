@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.BackgroundOptions
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.SuggestedHost
import dev.claudefleet.mobile.model.hostProposalLine
import dev.claudefleet.mobile.model.suggestedHostOf
import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.JsonNull
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.NewBgSessionResult
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.QueuePromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StartPlan
import dev.claudefleet.mobile.model.StartPreview
import dev.claudefleet.mobile.model.StartProgress
import dev.claudefleet.mobile.model.StartStepState
import dev.claudefleet.mobile.model.backgroundAgentWord
import dev.claudefleet.mobile.model.backgroundAgents
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A hub on claude-fleet main: `new_session` takes a profile and a start token, and `queue_prompt` keeps a first message. */
private val MAIN_HUB = HubCapabilities(
    tools = setOf(
        HubCapabilities.NEW_SESSION,
        HubCapabilities.QUEUE_PROMPT,
        HubCapabilities.CHECK_ACCOUNT_HEADROOM,
        HubCapabilities.WORK,
        HubCapabilities.WORK_LINK,
    ),
    params = mapOf(
        HubCapabilities.NEW_SESSION to setOf("host_alias", "project_id", "profile", "over_limit_ok", "start_token"),
        HubCapabilities.WORK_LINK to setOf("action", "key", "host_alias", "project_id", "worktree"),
    ),
)

private class StartFleet(caps: HubCapabilities = MAIN_HUB) : FleetState {
    override val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    override val hosts = MutableStateFlow(listOf(HostRow("pine", reachable = true), HostRow("box", reachable = true)))
    override val projects = MutableStateFlow(listOf(ProjectRow(3, owner = "me", repo = "repo", lastSessionAt = 100)))
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override val accountNames = MutableStateFlow(mapOf("acc-1" to "m.janci@32bit.sk", "acc-2" to "team@32bit.sk"))
    override val startProgress = MutableSharedFlow<StartProgress>(extraBufferCapacity = 16)
    override suspend fun refresh() {}
}

private class StartActions : NewSessionActions {
    val requests = mutableListOf<NewSessionRequest>()
    val messages = mutableListOf<Pair<Long, String>>()
    val headroomFor = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var failMessage: Throwable? = null
    val proposedFor = mutableListOf<Long>()
    var proposal: SuggestedHost? = null
    var refuseProposal: Throwable? = null
    val backgrounds = mutableListOf<BackgroundOptions>()
    var headroomAnswer = Headroom(
        pauseAtPct = 90.0,
        chosen = HostLogin(profile = null, accountUuid = "acc-1", usedPct = 25.0),
        logins = listOf(
            HostLogin(profile = null, accountUuid = "acc-1", usedPct = 25.0),
            HostLogin(profile = "team", accountUuid = "acc-2", usedPct = 95.0),
        ),
    )

    override suspend fun newSession(request: NewSessionRequest): SessionRow {
        requests += request
        gate?.await()
        return SessionRow(id = 41, tmuxName = "dev-me-repo", hostAlias = request.hostAlias)
    }

    override suspend fun newBackground(hostAlias: String, name: String, prompt: String) =
        NewBgSessionResult(claudeSessionId = "c", session = null)

    override suspend fun proposeHost(projectId: Long): SuggestedHost? {
        proposedFor += projectId
        refuseProposal?.let { throw it }
        return proposal
    }

    override suspend fun newBackground(hostAlias: String, name: String, prompt: String, options: BackgroundOptions): NewBgSessionResult {
        backgrounds += options
        return NewBgSessionResult(claudeSessionId = "c", session = null)
    }

    override suspend fun headroom(hostAlias: String): Headroom {
        headroomFor += hostAlias
        return headroomAnswer
    }

    override suspend fun firstMessage(sessionId: Long, prompt: String): QueuePromptResult {
        messages += sessionId to prompt
        failMessage?.let { throw it }
        return QueuePromptResult(sessionId = sessionId, delivered = false, queuedId = 5)
    }
}

/**
 * Gap plan G5.6, the MobileNewSession board on claude-fleet main: start from
 * a branch, the drafted branch, the Account row, the first message and the
 * real start steps.
 */
class NewSessionStartTest {

    private fun vm(
        fleet: FleetState,
        actions: NewSessionActions,
        scope: kotlinx.coroutines.CoroutineScope,
        opened: MutableList<Long> = mutableListOf(),
        drafts: DraftMemory? = null,
    ) = NewSessionViewModel(
        fleet, actions, scope, canWrite = true, initialHost = "pine", onCreated = { opened += it },
        drafts = drafts, now = { 1_000L },
    )

    // ---- start from a branch ----

    @Test
    fun a_branch_start_checks_the_branch_out_in_its_own_worktree() = runTest {
        val actions = StartActions()
        val vm = vm(StartFleet(), actions, backgroundScope)
        vm.setStartFrom(StartFrom.Branch)
        vm.selectProject(3)
        vm.onBranchChange("fix/login")
        runCurrent()

        assertEquals(StartFrom.Branch, vm.state.value.startFrom)
        assertTrue(vm.state.value.canCreate)
        vm.create()
        runCurrent()

        val sent = actions.requests.single()
        // `base == name` is the hub script's "check this branch out" (lifecycle.rs worktree_add_script).
        assertEquals("fix/login", sent.newWorktree)
        assertEquals("fix/login", sent.baseBranch)
        assertNull(sent.worktreeId)
    }

    @Test
    fun a_branch_start_needs_a_branch_and_not_the_projects_own() = runTest {
        val vm = vm(StartFleet(), StartActions(), backgroundScope)
        vm.setStartFrom(StartFrom.Branch)
        vm.selectProject(3)
        runCurrent()
        assertFalse(vm.state.value.canCreate)
        assertEquals("Name the branch to start from.", vm.state.value.missing)

        vm.onBranchChange("main")
        runCurrent()
        assertFalse(vm.state.value.canCreate, "the hub refuses main as a new worktree")
        assertEquals("main is the project's own checkout: start from A project to work there.", vm.state.value.missing)
        assertTrue(vm.state.value.branchInvalid)

        vm.setStartFrom(StartFrom.Project)
        runCurrent()
        assertTrue(vm.state.value.canCreate, "from a project, the branch field is not asked")
    }

    // ---- the real start steps ----

    @Test
    fun a_start_carries_a_token_and_ticks_its_steps_off_on_the_hubs_frames() = runTest {
        val fleet = StartFleet()
        val actions = StartActions().apply { gate = CompletableDeferred() }
        val opened = mutableListOf<Long>()
        val vm = vm(fleet, actions, backgroundScope, opened)
        vm.selectProject(3)
        runCurrent()
        vm.create()
        runCurrent()

        val token = assertNotNull(actions.requests.single().startToken)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]{1,64}")), "the hub's validate_start_token shape: $token")
        assertEquals(listOf(StartStepState.PENDING, StartStepState.PENDING, StartStepState.PENDING), vm.state.value.startSteps?.values?.toList())

        fleet.startProgress.emit(StartProgress(token, "worktree", 1, 3, "started"))
        fleet.startProgress.emit(StartProgress(token, "worktree", 1, 3, "done"))
        fleet.startProgress.emit(StartProgress(token, "tmux", 2, 3, "started"))
        // Another device's start: not this form's.
        fleet.startProgress.emit(StartProgress("st-other", "agent", 3, 3, "failed"))
        runCurrent()

        val steps = assertNotNull(vm.state.value.startSteps)
        assertEquals(StartStepState.DONE, steps["worktree"])
        assertEquals(StartStepState.STARTED, steps["tmux"])
        assertEquals(StartStepState.PENDING, steps["agent"])
        assertEquals(
            listOf("Checkout found", "Opening the tmux pane", "Claude Code"),
            startLines(vm.state.value).map { it.text },
        )

        actions.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(41L), opened)
    }

    @Test
    fun an_older_hub_gets_no_token_and_the_steps_stay_unticked() = runTest {
        val actions = StartActions()
        val vm = vm(StartFleet(MAIN_HUB.copy(params = emptyMap())), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()
        vm.create()
        runCurrent()

        assertNull(actions.requests.single().startToken)
        assertNull(vm.state.value.startSteps)
    }

    // ---- the first message ----

    @Test
    fun the_first_message_is_kept_by_the_hub_once_the_session_exists() = runTest {
        val actions = StartActions()
        val opened = mutableListOf<Long>()
        val vm = vm(StartFleet(), actions, backgroundScope, opened)
        vm.selectProject(3)
        vm.onFirstMessageChange("  Apply the Orbit tokens.  ")
        runCurrent()
        assertTrue(vm.state.value.firstMessageAvailable)

        vm.create()
        runCurrent()

        assertEquals(listOf(41L to "Apply the Orbit tokens."), actions.messages)
        assertEquals(listOf(41L), opened)
    }

    @Test
    fun a_first_message_the_hub_would_not_keep_waits_in_the_sessions_box() = runTest {
        val actions = StartActions().apply { failMessage = HubError.Tool("E_BUSY", "no") }
        val drafts = DraftMemory()
        val opened = mutableListOf<Long>()
        val vm = vm(StartFleet(), actions, backgroundScope, opened, drafts)
        vm.selectProject(3)
        vm.onFirstMessageChange("Apply the Orbit tokens.")
        runCurrent()
        vm.create()
        runCurrent()

        assertEquals(listOf(41L), opened, "the session was made: it opens")
        assertEquals("Apply the Orbit tokens.", drafts.recall(41L), "unsent, in its own box")
    }

    @Test
    fun no_first_message_is_offered_without_queue_prompt_or_sent_when_empty() = runTest {
        val actions = StartActions()
        val bare = vm(StartFleet(MAIN_HUB.copy(tools = MAIN_HUB.tools - HubCapabilities.QUEUE_PROMPT)), actions, backgroundScope)
        runCurrent()
        assertFalse(bare.state.value.firstMessageAvailable)

        val vm = vm(StartFleet(), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()
        vm.create()
        runCurrent()
        assertTrue(actions.messages.isEmpty())
    }

    // ---- the Account row ----

    @Test
    fun the_account_row_names_the_hosts_login_and_its_room() = runTest {
        val actions = StartActions()
        val vm = vm(StartFleet(), actions, backgroundScope)
        runCurrent()

        assertEquals(listOf("pine"), actions.headroomFor)
        val account = assertNotNull(vm.state.value.account)
        assertEquals("m.janci@32bit.sk · 75% left", accountLine(account))
        assertEquals(2, vm.state.value.logins.size)

        vm.selectHost("box")
        runCurrent()
        assertEquals(listOf("pine", "box"), actions.headroomFor, "read again for the new host")
    }

    @Test
    fun the_default_login_is_left_to_the_hub_and_a_picked_one_is_sent() = runTest {
        val actions = StartActions()
        val vm = vm(StartFleet(), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()
        vm.create()
        runCurrent()
        assertNull(actions.requests.last().profile, "nothing picked: the hub's own choice, unchanged")
        assertFalse(actions.requests.last().overLimitOk)

        val second = vm(StartFleet(), actions, backgroundScope)
        second.selectProject(3)
        runCurrent()
        second.pickLogin("team")
        runCurrent()
        val picked = assertNotNull(second.state.value.account)
        assertTrue(picked.over)
        assertEquals("team (team@32bit.sk) · 5% left · past the pause line", accountLine(picked))
        second.create()
        runCurrent()
        assertEquals("team", actions.requests.last().profile)
        assertTrue(actions.requests.last().overLimitOk, "the person chose a login past the line, knowing it")
    }

    @Test
    fun a_login_the_host_does_not_have_is_ignored() = runTest {
        val vm = vm(StartFleet(), StartActions(), backgroundScope)
        runCurrent()
        vm.pickLogin("nobody")
        runCurrent()
        assertEquals("m.janci@32bit.sk · 75% left", accountLine(assertNotNull(vm.state.value.account)))
    }

    @Test
    fun no_account_row_without_the_hubs_headroom() = runTest {
        val actions = StartActions()
        val vm = vm(StartFleet(MAIN_HUB.copy(tools = MAIN_HUB.tools - HubCapabilities.CHECK_ACCOUNT_HEADROOM)), actions, backgroundScope)
        runCurrent()
        assertNull(vm.state.value.account)
        assertTrue(actions.headroomFor.isEmpty())
    }

    // ---- ticket mode: the drafted branch ----

    private fun ticketVm(fleet: FleetState, work: FakeWorkActions, scope: kotlinx.coroutines.CoroutineScope) =
        NewSessionViewModel(
            fleet, StartActions(), scope, canWrite = true, initialHost = "pine", onCreated = {},
            ticketKey = "FLEET-151", workActions = work,
        )

    @Test
    fun the_hubs_planned_branch_is_shown_as_drafted_and_not_sent_as_the_persons() = runTest {
        val work = FakeWorkActions().apply {
            previewAnswer = StartPreview(key = "FLEET-151", plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit-redesign"))
        }
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()

        assertEquals(listOf("preview FLEET-151 pine -"), work.previewCalls)
        val s = vm.state.value
        assertEquals("fleet-151-orbit-redesign", s.branchDraft)
        assertEquals("fleet-151-orbit-redesign", s.ticketBranch)
        assertEquals("Drafted from FLEET-151", draftedFrom(s))
        assertTrue(s.ticketBranchEditable)

        vm.create()
        runCurrent()
        assertEquals(listOf("start FLEET-151 pine -"), work.calls, "the draft is the hub's own name: nothing extra sent")
    }

    @Test
    fun an_edited_branch_is_the_persons_and_is_sent() = runTest {
        val work = FakeWorkActions().apply {
            previewAnswer = StartPreview(plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit"))
        }
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()
        vm.onTicketBranchChange("orbit-redesign")
        runCurrent()
        assertNull(draftedFrom(vm.state.value), "no longer the draft")

        vm.create()
        runCurrent()
        assertEquals(listOf("start FLEET-151 pine - wt=orbit-redesign"), work.calls)
    }

    @Test
    fun clear_empties_the_draft_and_leaves_the_name_to_the_hub() = runTest {
        val work = FakeWorkActions().apply {
            previewAnswer = StartPreview(plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit"))
        }
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()
        vm.clearBranchDraft()
        runCurrent()

        assertEquals("", vm.state.value.ticketBranch)
        assertTrue(vm.state.value.canCreate)
        vm.create()
        runCurrent()
        assertEquals(listOf("start FLEET-151 pine -"), work.calls)
    }

    @Test
    fun a_new_host_or_project_asks_for_its_own_draft() = runTest {
        val work = FakeWorkActions().apply {
            previewAnswer = StartPreview(plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit"))
        }
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()
        vm.selectProject(3)
        runCurrent()
        assertEquals(listOf("preview FLEET-151 pine -", "preview FLEET-151 pine 3"), work.previewCalls)
    }

    @Test
    fun regenerate_asks_for_the_draft_again_and_shows_it_as_drafted() = runTest {
        val work = FakeWorkActions().apply {
            previewAnswer = StartPreview(plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit"))
        }
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()
        work.previewAnswer = StartPreview(plan = StartPlan(projectId = 3, hostAlias = "pine", branch = "fleet-151-orbit-tokens"))
        assertNotNull(vm.regenerateBranchDraft())
        runCurrent()

        assertEquals(listOf("preview FLEET-151 pine -", "preview FLEET-151 pine -"), work.previewCalls, "the same draft call, again")
        assertEquals("fleet-151-orbit-tokens", vm.state.value.ticketBranch)
        assertEquals("Drafted from FLEET-151", draftedFrom(vm.state.value))
    }

    // ---- Jev's host (MobileNewSession, Step 1) ----

    private val proposing = MAIN_HUB.copy(tools = MAIN_HUB.tools + HubCapabilities.PROPOSE_HOST_PLACEMENT)

    private fun unfiltered(fleet: FleetState, actions: NewSessionActions, scope: kotlinx.coroutines.CoroutineScope) =
        NewSessionViewModel(fleet, actions, scope, canWrite = true, initialHost = null, onCreated = {})

    @Test
    fun jevs_host_is_preselected_with_why_and_change_hands_the_choice_back() = runTest {
        val actions = StartActions().apply { proposal = SuggestedHost("box", confidencePct = 80, reason = "last 4 sessions on repo ran here") }
        val vm = unfiltered(StartFleet(proposing), actions, backgroundScope)
        vm.selectProject(3)
        runCurrent()

        assertEquals(listOf(3L), actions.proposedFor)
        assertEquals("box", vm.state.value.host)
        assertEquals(HostProposal("box", "Proposed by Jev · last 4 sessions on repo ran here"), vm.state.value.hostProposal)

        vm.changeProposedHost()
        runCurrent()
        assertNull(vm.state.value.hostProposal)
        assertNull(vm.state.value.host, "two hosts and no proposal: the person picks")
        assertEquals(listOf(3L), actions.proposedFor, "not asked again for the same project")
    }

    @Test
    fun no_proposal_from_an_older_hub_after_a_pick_or_on_a_refusal() = runTest {
        val actions = StartActions().apply { proposal = SuggestedHost("box") }
        val older = unfiltered(StartFleet(), actions, backgroundScope)
        older.selectProject(3)
        runCurrent()
        assertTrue(actions.proposedFor.isEmpty(), "never a tool the hub does not list")

        val picked = unfiltered(StartFleet(proposing), actions, backgroundScope)
        picked.selectHost("pine")
        picked.selectProject(3)
        runCurrent()
        assertTrue(actions.proposedFor.isEmpty(), "the person chose already")
        assertEquals("pine", picked.state.value.host)

        val refusing = StartActions().apply { refuseProposal = HubError.Tool("E_FORBIDDEN", "not for this device") }
        val vm = unfiltered(StartFleet(proposing), refusing, backgroundScope)
        vm.selectProject(3)
        runCurrent()
        assertEquals(listOf(3L), refusing.proposedFor)
        assertNull(vm.state.value.hostProposal, "hidden on a refusal")
        assertNull(vm.state.value.host)
    }

    @Test
    fun the_proposal_reads_the_hubs_answer_or_its_null() {
        assertEquals(
            SuggestedHost("mercury", confidencePct = 72, runId = 9),
            suggestedHostOf(json.parseToJsonElement("""{"host_alias":"mercury","confidence_pct":72,"run_id":9}""")),
        )
        assertEquals("mercury", suggestedHostOf(json.parseToJsonElement("""{"suggestion":{"host_alias":"mercury"}}"""))?.hostAlias)
        assertNull(suggestedHostOf(JsonNull))
        assertNull(suggestedHostOf(json.parseToJsonElement("""{"suggestion":null}""")))
        assertEquals("Proposed by Jev", hostProposalLine(SuggestedHost("mercury", reason = " ")))
    }

    // ---- the background agent's Agent (MobileMissions) ----

    @Test
    fun codex_is_offered_only_where_the_hub_lists_it_and_claude_is_never_sent() = runTest {
        assertEquals(listOf("claude"), backgroundAgents(null))
        assertEquals(listOf("claude"), backgroundAgents(setOf("claude")))
        assertEquals(listOf("claude", "codex"), backgroundAgents(setOf("claude", "codex")))
        assertEquals(listOf("Claude Code", "Codex"), listOf("claude", "codex").map(::backgroundAgentWord))

        val bg = MAIN_HUB.copy(
            tools = MAIN_HUB.tools + HubCapabilities.NEW_BG_SESSION,
            params = MAIN_HUB.params + (HubCapabilities.NEW_BG_SESSION to setOf("host_alias", "name", "prompt", "agent", "read_only")),
            paramValues = mapOf(HubCapabilities.NEW_BG_SESSION to mapOf("agent" to setOf("claude", "codex"))),
        )
        val claudeOnly = vm(StartFleet(MAIN_HUB.copy(tools = MAIN_HUB.tools + HubCapabilities.NEW_BG_SESSION)), StartActions(), backgroundScope)
        val actions = StartActions()
        val vm = vm(StartFleet(bg), actions, backgroundScope)
        runCurrent()
        assertEquals(listOf("claude"), claudeOnly.state.value.backgroundAgents, "a hub that enumerates no agent runs Claude alone")
        assertEquals(listOf("claude", "codex"), vm.state.value.backgroundAgents)
        vm.startBackground("", "find every Command::new", BackgroundOptions(agent = "codex"), onUntracked = {})
        runCurrent()
        assertEquals("codex", actions.backgrounds.single().agent)
    }

    // ---- the words ----

    @Test
    fun the_starting_panel_lists_the_steps_unticked_without_the_hubs_frames() {
        val s = NewSessionUiState(startFrom = StartFrom.Branch, branch = "fix/login")
        assertEquals(listOf("Worktree", "tmux pane", "Claude Code"), startSteps(s), "a branch start makes a worktree")
        assertEquals(listOf(null, null, null), startLines(s).map { it.state })

        val live = s.copy(startSteps = mapOf("worktree" to StartStepState.DONE, "tmux" to StartStepState.FAILED, "agent" to StartStepState.PENDING))
        assertEquals(listOf("Worktree created", "tmux pane not opened", "Claude Code"), startLines(live).map { it.text })
    }

    @Test
    fun the_review_names_the_branch_start_and_the_persons_ticket_branch() {
        assertEquals("fix/login, in its own worktree", worktreeLine(NewSessionUiState(startFrom = StartFrom.Branch, branch = "fix/login"), null))
        assertEquals("New: orbit-redesign", worktreeLine(NewSessionUiState(ticketKey = "K-1", ticketBranch = "orbit-redesign"), null))
        assertEquals("Named after K-1 by the hub", worktreeLine(NewSessionUiState(ticketKey = "K-1"), null))
        assertEquals("The project's own checkout", worktreeLine(NewSessionUiState(), null))
    }

    @Test
    fun a_typed_first_message_or_branch_is_asked_about_before_leaving() {
        assertTrue(newSessionTyped(NewSessionUiState(firstMessage = "hi")))
        assertTrue(newSessionTyped(NewSessionUiState(ticketKey = "K-1", ticketBranch = "mine", ticketBranchEdited = true)))
        assertFalse(newSessionTyped(NewSessionUiState(ticketKey = "K-1", ticketBranch = "drafted")), "a draft is not something the person typed")
    }

    @Test
    fun no_draft_when_the_hub_has_no_plan() = runTest {
        val work = FakeWorkActions()
        val vm = ticketVm(StartFleet(), work, backgroundScope)
        runCurrent()
        assertNull(vm.state.value.branchDraft)
        assertNull(draftedFrom(vm.state.value))
        assertTrue(vm.state.value.canCreate, "a failed preview never blocks the start")
    }
}
