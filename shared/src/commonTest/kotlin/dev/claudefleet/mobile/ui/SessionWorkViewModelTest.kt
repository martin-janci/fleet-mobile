@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.TimelineFrame
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LocalWorkItem
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TicketCard
import dev.claudefleet.mobile.model.Today
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fleet whose capabilities the test sets, and which forgets actions the way the repository does. */
internal class WorkFleet(
    rows: List<SessionRow> = emptyList(),
    caps: HubCapabilities = FULL,
    hostRows: List<HostRow> = emptyList(),
    projectRows: List<ProjectRow> = emptyList(),
) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow(projectRows)
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override val tickets = MutableStateFlow<List<Ticket>>(emptyList())
    override val myWork = MutableStateFlow<Set<Long>?>(null)
    override val orgs = MutableStateFlow(OrgDirectory.EMPTY)
    override val timeline = MutableSharedFlow<TimelineFrame>(extraBufferCapacity = 16)
    val remembered = mutableListOf<Ticket>()

    override suspend fun refresh() = Unit

    override fun actionMissing(tool: String, action: String) {
        capabilities.update { it.forgetting(tool, action) }
    }

    override fun rememberTickets(tickets: List<Ticket>) {
        remembered += tickets
    }

    companion object {
        val FULL = HubCapabilities.of(ToolCatalog(setOf("work", "work_link")))
    }
}

/** Records every call; each answers what the test put in its slot, or throws [fail]. */
internal class FakeWorkActions : WorkActions {
    val calls = mutableListOf<String>()
    var fail: Throwable? = null
    var failLookup: Throwable? = null
    var lookupAnswer = Ticket(id = 70, key = "PAY-7", title = "Refund")
    var ticketsAnswer: Map<String, List<Ticket>> = emptyMap()
    var planAnswer: ResumePlan? = null
    var started = SessionRow(id = 99, tmuxName = "pay-9", hostAlias = "pine")

    private fun record(call: String): SessionRow {
        calls += call
        fail?.let { throw it }
        return started
    }

    override suspend fun tickets(view: String): List<Ticket> {
        calls += "tickets $view"
        fail?.let { throw it }
        return ticketsAnswer[view].orEmpty()
    }

    override suspend fun lookup(keyOrUrl: String): Ticket {
        calls += "lookup $keyOrUrl"
        failLookup?.let { throw it }
        return lookupAnswer
    }

    override suspend fun resumePlan(key: String): ResumePlan {
        calls += "resume_plan $key"
        return planAnswer ?: throw HubError.Tool("E_NOTFOUND", "no past work")
    }

    override suspend fun confirm(sessionId: Long, linkId: Long) = record("confirm $sessionId $linkId")
    override suspend fun reject(sessionId: Long, linkId: Long) = record("reject $sessionId $linkId")
    override suspend fun unlink(sessionId: Long, linkId: Long) = record("unlink $sessionId $linkId")
    override suspend fun link(sessionId: Long, itemId: Long?, key: String?) = record("link $sessionId ${itemId ?: key}")
    override suspend fun start(key: String, hostAlias: String, projectId: Long?) = record("start $key $hostAlias ${projectId ?: "-"}")
    override suspend fun resume(key: String, hostAlias: String?) = record("resume $key ${hostAlias ?: "-"}")

    // The reads M8.6 added are kept out of [calls], which the older tests
    // pin exactly: a card read beside a resume plan is not a decision.
    var todayAnswer = Today()
    var failToday: Throwable? = null
    val todayCalls = mutableListOf<Long>()
    var cardAnswer: TicketCard? = null
    val cardCalls = mutableListOf<String>()

    override suspend fun today(since: Long): Today {
        todayCalls += since
        failToday?.let { throw it }
        return todayAnswer
    }

    override suspend fun card(key: String): TicketCard {
        cardCalls += key
        return cardAnswer ?: throw HubError.Tool("E_NOTFOUND", "no card")
    }

    override suspend fun handover(sessionId: Long) = record("handover $sessionId")

    // `work local_items` is a read, kept out of [calls] like the other reads.
    var localItemsAnswer: List<LocalWorkItem> = emptyList()
    var localItemsCalls = 0
    var named: SessionRow? = null

    override suspend fun localItems(): List<LocalWorkItem> {
        localItemsCalls++
        return localItemsAnswer
    }

    override suspend fun nameWork(sessionId: Long, title: String, key: String?): SessionRow {
        val row = record("name $sessionId $title ${key ?: "-"}")
        return named ?: row
    }

    override suspend fun renameWork(itemId: Long, title: String): LocalWorkItem {
        record("rename $itemId $title")
        return LocalWorkItem(itemId, title = title)
    }
}

private val PAY7 = WorkSummary(linkId = 11, itemId = 70, key = "PAY-7", title = "Refund retries", source = "branch", state = "confirmed")
private val PAY9_GUESS = WorkSummary(linkId = 12, itemId = 90, key = "PAY-9", title = "Ledger", source = "prompt", state = "suggested", rule = "R5")

private fun row(work: WorkSummary? = null, guess: WorkSummary? = null) =
    SessionRow(id = 5, tmuxName = "dev", hostAlias = "pine", work = work, workSuggested = guess)

class SessionWorkViewModelTest {

    @Test
    fun confirm_and_reject_call_the_right_link_id() = runTest {
        val fleet = WorkFleet(listOf(row(work = PAY7, guess = PAY9_GUESS)))
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)

        assertTrue(vm.state.value.canConfirm)
        vm.confirm()
        runCurrent()
        vm.reject()
        runCurrent()

        assertEquals(listOf("confirm 5 12", "reject 5 12"), actions.calls, "the suggestion's link, never the confirmed one")
    }

    @Test
    fun clear_unlinks_the_confirmed_link() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7))), actions, backgroundScope, canWrite = true)

        assertTrue(vm.state.value.canClear)
        assertFalse(vm.state.value.canConfirm, "no suggestion: nothing to confirm")
        vm.clear()
        runCurrent()

        assertEquals(listOf("unlink 5 11"), actions.calls)
    }

    /** The non-negotiable: a readonly token never sees a write button — and a tap that races it calls nothing. */
    @Test
    fun a_readonly_token_sees_no_buttons() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7, guess = PAY9_GUESS))), actions, backgroundScope, canWrite = false)

        val s = vm.state.value
        assertNotNull(s.chip, "reading is fine")
        assertFalse(s.canConfirm || s.canReject || s.canClear || s.canSetWork)
        vm.confirm(); vm.reject(); vm.clear(); vm.setWork("PAY-1")
        runCurrent()
        assertEquals(emptyList(), actions.calls)
    }

    /** The second gate: a full credential against a hub that hides `work_link` from it is offered nothing. */
    @Test
    fun a_hub_that_does_not_serve_work_link_gets_no_buttons_either() = runTest {
        val caps = HubCapabilities.of(ToolCatalog(setOf("work")))
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(guess = PAY9_GUESS)), caps), actions, backgroundScope, canWrite = true)

        assertFalse(vm.state.value.canConfirm || vm.state.value.canSetWork)
        vm.confirm()
        runCurrent()
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun an_action_the_schema_does_not_enumerate_is_not_offered() = runTest {
        val caps = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("link", "unlink", "reject"))))
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(guess = PAY9_GUESS)), caps), FakeWorkActions(), backgroundScope, canWrite = true)

        assertFalse(vm.state.value.canConfirm, "a hub before M4 has no confirm")
        assertTrue(vm.state.value.canReject)
    }

    /** A hub whose `action` is a free string says so only by refusing: that action goes for the connection. */
    @Test
    fun an_unknown_action_refusal_hides_that_action_for_the_connection() = runTest {
        val fleet = WorkFleet(listOf(row(guess = PAY9_GUESS)))
        val actions = FakeWorkActions().apply {
            fail = HubError.Tool("E_INVALID", "unknown work_link action \"confirm\"; one of link, reject, unlink")
        }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)

        vm.confirm()
        runCurrent()

        assertFalse(vm.state.value.canConfirm)
        assertTrue(vm.state.value.canReject, "only the refused action goes")
        assertEquals("This hub can't do that yet", vm.state.value.error?.title)
        vm.confirm()
        runCurrent()
        assertEquals(1, actions.calls.size, "never asked twice on the same connection")
    }

    @Test
    fun set_work_links_a_looked_up_ticket_by_its_item() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row())), actions, backgroundScope, canWrite = true)

        assertTrue(vm.state.value.canSetWork)
        vm.setWork("  https://acme.atlassian.net/browse/PAY-7 ")
        runCurrent()

        assertEquals(listOf("lookup https://acme.atlassian.net/browse/PAY-7", "link 5 70"), actions.calls)
    }

    /** A key no tracker knows still names work — trackers never gate it. A URL that does not resolve is an error. */
    @Test
    fun set_work_falls_back_to_the_bare_key_and_never_to_a_bad_url() = runTest {
        val actions = FakeWorkActions().apply { failLookup = HubError.Tool("E_NOTFOUND", "no tracker knows PAY-7") }
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row())), actions, backgroundScope, canWrite = true)

        vm.setWork("PAY-7")
        runCurrent()
        assertEquals(listOf("lookup PAY-7", "link 5 PAY-7"), actions.calls)

        actions.calls.clear()
        vm.setWork("https://elsewhere.example/PAY-7")
        runCurrent()
        assertEquals(listOf("lookup https://elsewhere.example/PAY-7"), actions.calls)
        assertEquals("Not found", vm.state.value.error?.title, "not \"this session is gone\"")
    }

    @Test
    fun a_hub_without_the_work_graph_draws_no_chip() = runTest {
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7)), HubCapabilities()), FakeWorkActions(), backgroundScope, canWrite = true)

        assertNull(vm.state.value.chip)
        vm.openSheet()
        runCurrent()
        assertFalse(vm.state.value.sheetOpen)
    }

    /** The chip follows the row: a `session:updated` that confirms the guess is what moves it, not the reply. */
    @Test
    fun the_chip_follows_the_row() = runTest {
        val fleet = WorkFleet(listOf(row(guess = PAY9_GUESS)))
        val vm = SessionWorkViewModel(5, fleet, FakeWorkActions(), backgroundScope, canWrite = true)
        assertEquals("PAY-9", vm.state.value.chip?.key)
        assertNull(vm.state.value.work)

        fleet.sessions.value = listOf(row(work = PAY9_GUESS.copy(state = "confirmed")))
        runCurrent()
        assertEquals("PAY-9", vm.state.value.work?.key)
        assertFalse(vm.state.value.canConfirm)
    }

    @Test
    fun why_is_said_in_the_desktops_words() {
        assertEquals("linked from the branch", workWhy(PAY7))
        assertEquals("linked from a prompt · rule R5", workWhy(PAY9_GUESS))
        assertEquals("linked from a prompt · rule R5 · weak guess", workWhy(PAY9_GUESS.copy(strength = "weak")))
        assertEquals("linked (jira-sync)", workWhy(PAY7.copy(source = "jira-sync")))
    }

    /**
     * Claude's answer to the classification nudge (claude-fleet M4.6, R11)
     * is said as the desktop says it, and its `inferred` strength is not
     * tacked on as "· inferred guess": the sentence already says whose guess.
     */
    @Test
    fun an_answer_claude_gave_when_asked_is_said_as_such() {
        val asked = PAY9_GUESS.copy(source = "agent_inferred", strength = "inferred", rule = "R11", preselected = true)
        assertEquals("suggested by Claude when asked · rule R11", workWhy(asked))
        assertEquals("named by Claude when asked", workWhy(asked.copy(state = "confirmed", rule = null)))
    }

    /** The session screen's chip follows the ticket cache, as the list does: a `work:item` does not restamp the row. */
    @Test
    fun the_chip_follows_the_ticket_cache() = runTest {
        val stamped = WorkSummary(linkId = 3, itemId = 70, key = "PAY-7", title = "Refund", source = "manual", statusName = "To Do")
        val fleet = WorkFleet(listOf(row(work = stamped)))
        val vm = SessionWorkViewModel(5, fleet, FakeWorkActions(), backgroundScope, canWrite = true)
        runCurrent()
        assertEquals("To Do", vm.state.value.chip?.statusName)

        fleet.tickets.value = listOf(Ticket(id = 70, key = "PAY-7", title = "Refund", statusName = "Done"))
        runCurrent()

        assertEquals("Done", vm.state.value.chip?.statusName)
        assertEquals(3L, vm.state.value.work?.linkId, "decisions still address the row's link")
    }

    /**
     * A URL is linked only by the item it resolves to. The hub takes any
     * string of up to 64 characters as a free-form key, so a URL sent as one
     * would become a work group named after the URL.
     */
    @Test
    fun without_lookup_a_url_is_refused_and_never_linked_as_a_key() = runTest {
        val actions = FakeWorkActions()
        val noLookup = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work" to setOf("links"))))
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = noLookup), actions, backgroundScope, canWrite = true)

        vm.setWork("https://acme.atlassian.net/browse/PAY-7")
        runCurrent()
        assertEquals(emptyList(), actions.calls)
        assertEquals("This hub can't look up a ticket link", vm.state.value.error?.title)

        // A bare key still stands on its own: trackers never gate work.
        vm.setWork("PAY-7")
        runCurrent()
        assertEquals(listOf("link 5 PAY-7"), actions.calls)
    }

    /**
     * An "unknown action" refusal of `work lookup` is about `lookup`, not
     * about `work_link link`: Set work… stays, only the lookup is forgotten,
     * and the URL is not linked as a key.
     */
    @Test
    fun a_lookup_refused_as_unknown_hides_only_the_lookup() = runTest {
        val actions = FakeWorkActions().apply {
            failLookup = HubError.Tool("E_INVALID", "unknown work action \"lookup\"; one of links, context")
        }
        val fleet = WorkFleet(listOf(row()))
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)

        vm.setWork("https://acme.atlassian.net/browse/PAY-7")
        runCurrent()

        assertTrue(fleet.capabilities.value.has("work_link", "link"), "Set work… must survive a lookup refusal")
        assertTrue(vm.state.value.canSetWork)
        assertFalse(fleet.capabilities.value.has("work", "lookup"))
        assertEquals(listOf("lookup https://acme.atlassian.net/browse/PAY-7"), actions.calls, "the URL is not linked as a key")
        assertEquals("This hub can't look up a ticket link", vm.state.value.error?.title)

        // A bare key with the lookup refused as unknown falls back to the key.
        actions.calls.clear()
        vm.setWork("PAY-7")
        runCurrent()
        assertEquals(listOf("link 5 PAY-7"), actions.calls, "lookup is now known missing: straight to the key")
    }

    // ---- handover on demand (claude-fleet M9.3) ----

    private fun running(work: WorkSummary? = PAY7, guess: WorkSummary? = null) = row(work, guess).copy(status = "running")

    @Test
    fun a_handover_is_offered_for_a_running_linked_session_to_a_write_token_only() = runTest {
        fun canHandover(r: SessionRow, canWrite: Boolean = true, caps: HubCapabilities = WorkFleet.FULL) =
            SessionWorkViewModel(5, WorkFleet(listOf(r), caps = caps), FakeWorkActions(), backgroundScope, canWrite).state.value.canHandover

        assertTrue(canHandover(running()))
        assertFalse(canHandover(running(), canWrite = false), "a readonly token")
        assertFalse(canHandover(running(work = null, guess = PAY9_GUESS)), "a guess is not the session's work")
        assertFalse(canHandover(running(work = PAY7.copy(key = null))), "no key to write it against")
        assertFalse(canHandover(row(PAY7).copy(status = "exited")), "nobody to ask")
        assertFalse(canHandover(running().copy(tmuxName = "bg:1234")), "a background agent")
        assertFalse(canHandover(running().copy(kind = "shell")), "a shell")
        val noHandover = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("confirm", "link"))))
        assertFalse(canHandover(running(), caps = noHandover), "a hub before M9.3")
    }

    /** The call only types the request in; the timeline says when the note is written. */
    @Test
    fun a_handover_is_asked_then_followed_on_the_timeline() = runTest {
        val fleet = WorkFleet(listOf(running()))
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)
        runCurrent()

        vm.handover().join()
        runCurrent()
        assertEquals(listOf("handover 5"), actions.calls)
        assertEquals(HandoverStatus.Requested, vm.state.value.handover)

        fleet.timeline.tryEmit(dev.claudefleet.mobile.data.TimelineFrame(6, "handover_written"))
        runCurrent()
        assertEquals(HandoverStatus.Requested, vm.state.value.handover, "another session's note is not this one's")

        fleet.timeline.tryEmit(dev.claudefleet.mobile.data.TimelineFrame(5, "prompt"))
        fleet.timeline.tryEmit(dev.claudefleet.mobile.data.TimelineFrame(5, "handover_written"))
        runCurrent()
        assertEquals(HandoverStatus.Written, vm.state.value.handover)

        fleet.timeline.tryEmit(dev.claudefleet.mobile.data.TimelineFrame(5, "handover_missing"))
        runCurrent()
        assertEquals(HandoverStatus.Missing, vm.state.value.handover)
    }

    /** The hub's refusals are said for what they mean on this sheet. */
    @Test
    fun a_refused_handover_says_why() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(running())), actions, backgroundScope, canWrite = true)
        runCurrent()

        actions.fail = HubError.Tool("E_NOT_ALIVE", "session is working")
        vm.handover().join()
        runCurrent()
        assertEquals("Claude can't write one right now", vm.state.value.error?.title)
        assertNull(vm.state.value.handover)

        actions.fail = HubError.Tool("E_EXISTS", "a handover was asked 3 min ago")
        vm.handover().join()
        runCurrent()
        assertEquals("A handover is already on its way", vm.state.value.error?.title)
        assertFalse(vm.state.value.error!!.isError)
    }

    /** A free-string hub that does not know `handover` hides the button for the connection, and nothing else. */
    @Test
    fun an_unknown_handover_hides_only_the_handover() = runTest {
        val fleet = WorkFleet(listOf(running()))
        val actions = FakeWorkActions().apply { fail = HubError.Tool("E_INVALID", "unknown work_link action \"handover\"; one of link") }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)
        runCurrent()
        vm.handover().join()
        runCurrent()
        assertFalse(vm.state.value.canHandover)
        assertTrue(vm.state.value.canClear, "the other decisions stay")
        assertEquals("This hub can't do that yet", vm.state.value.error?.title, "the hub is too old, not the session unsuited")
    }

    /** A readonly screen never reaches the tool, whatever calls `handover()`. */
    @Test
    fun a_readonly_token_never_asks() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(running())), actions, backgroundScope, canWrite = false)
        runCurrent()
        vm.handover().join()
        assertEquals(emptyList(), actions.calls)
    }

    // ---- naming local work (claude-fleet M11.1, decision D20) ----

    /** A hub whose schema lists `name` — and `local_items`, which came with it. */
    private val naming = HubCapabilities.of(
        ToolCatalog(
            setOf("work", "work_link"),
            mapOf("work" to setOf("links", "local_items"), "work_link" to setOf("link", "unlink", "name")),
        ),
    )

    private val LOCAL = WorkSummary(linkId = 21, itemId = 300, title = "Billing clean-up", source = "manual", state = "confirmed")

    @Test
    fun name_this_work_is_offered_to_a_full_token_on_a_hub_that_lists_name_only() = runTest {
        fun canName(r: SessionRow = row(), canWrite: Boolean = true, caps: HubCapabilities = naming) =
            SessionWorkViewModel(5, WorkFleet(listOf(r), caps = caps), FakeWorkActions(), backgroundScope, canWrite).state.value.canNameWork

        assertTrue(canName())
        assertTrue(canName(row(guess = PAY9_GUESS)), "a guess is not work yet")
        assertFalse(canName(canWrite = false), "a readonly token")
        assertFalse(canName(caps = HubCapabilities.of(ToolCatalog(setOf("work")))), "the hub hides work_link from a readonly token")
        assertFalse(
            canName(caps = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("link", "handover"))))),
            "a hub before M11.1 does not list name",
        )
        assertFalse(canName(caps = WorkFleet.FULL), "a free-string action is not a hub that lists name")
        assertFalse(canName(row(work = PAY7)), "the session already has work")
    }

    @Test
    fun naming_sends_the_title_and_the_key_trimmed() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = naming), actions, backgroundScope, canWrite = true)

        vm.nameWork("  Billing clean-up ", "  ").join()
        runCurrent()
        vm.nameWork("Billing clean-up", " BILL-1 ").join()

        assertEquals(listOf("name 5 Billing clean-up -", "name 5 Billing clean-up BILL-1"), actions.calls)
        assertNull(vm.state.value.error)
    }

    /** A title the hub would refuse is said on the phone, and never sent. */
    @Test
    fun a_title_the_hub_would_refuse_is_never_sent() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = naming), actions, backgroundScope, canWrite = true)

        vm.nameWork("   ").join()
        runCurrent()
        assertEquals("Give the work a name.", vm.state.value.error?.body)
        vm.nameWork("x".repeat(121)).join()
        runCurrent()
        assertEquals("Keep it to 120 characters.", vm.state.value.error?.body)
        vm.nameWork("two\nlines").join()
        runCurrent()
        assertEquals(emptyList(), actions.calls)

        assertNull(localWorkTitleProblem("é".repeat(120)))
        assertNull(localWorkTitleProblem("😀".repeat(120)), "the hub counts characters, not UTF-16 units")
    }

    @Test
    fun a_readonly_token_or_an_older_hub_never_names_or_renames() = runTest {
        val actions = FakeWorkActions().apply { localItemsAnswer = listOf(LocalWorkItem(300)) }
        val ro = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = naming), actions, backgroundScope, canWrite = false)
        ro.nameWork("Billing").join()
        val old = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = WorkFleet.FULL), actions, backgroundScope, canWrite = true)
        old.nameWork("Billing").join()
        val roLocal = SessionWorkViewModel(5, WorkFleet(listOf(row(work = LOCAL)), caps = naming), actions, backgroundScope, canWrite = false)
        roLocal.openSheet()
        roLocal.rename("New").join()
        runCurrent()

        assertEquals(emptyList(), actions.calls)
        assertEquals(0, actions.localItemsCalls, "not even the read behind Rename…")
        assertFalse(roLocal.state.value.canRename)
    }

    /** Rename only for local work: the hub's `local_items` says which, read when the sheet opens. */
    @Test
    fun rename_is_offered_for_local_work_only() = runTest {
        val actions = FakeWorkActions().apply { localItemsAnswer = listOf(LocalWorkItem(300, title = "Billing clean-up")) }
        val local = SessionWorkViewModel(5, WorkFleet(listOf(row(work = LOCAL)), caps = naming), actions, backgroundScope, canWrite = true)
        val ticket = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7)), caps = naming), actions, backgroundScope, canWrite = true)
        assertFalse(local.state.value.canRename, "not known to be local before the read")

        local.openSheet()
        ticket.openSheet()
        runCurrent()

        assertTrue(local.state.value.canRename)
        assertFalse(local.state.value.canNameWork, "it has work already")
        assertFalse(ticket.state.value.canRename, "a tracker's title belongs to the tracker")
        ticket.rename("Mine now").join()
        local.rename("  Billing, part 2 ").join()
        local.rename("Billing clean-up").join()
        assertEquals(listOf("rename 300 Billing, part 2"), actions.calls, "an unchanged title is not sent")
    }

    /** Named work is local by construction: Rename… follows without another read. */
    @Test
    fun work_just_named_can_be_renamed() = runTest {
        val fleet = WorkFleet(listOf(row()), caps = naming)
        val actions = FakeWorkActions().apply { named = row(work = LOCAL) }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)

        vm.nameWork("Billing clean-up").join()
        runCurrent()
        fleet.sessions.value = listOf(row(work = LOCAL)) // the hub's session:updated
        runCurrent()

        assertTrue(vm.state.value.canRename)
    }

    /** The hub's refusals are said in words, never as `work_link { action: link, key }`. */
    @Test
    fun a_refused_name_says_why() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row()), caps = naming), actions, backgroundScope, canWrite = true)

        actions.fail = HubError.Tool(
            "E_EXISTS",
            "PAY-7 is a tracker's ticket, not new work: link the session to it (work_link { action: link, key }) instead of naming it",
            kotlinx.serialization.json.buildJsonObject { put("item_id", kotlinx.serialization.json.JsonPrimitive(70)); put("tracker", kotlinx.serialization.json.JsonPrimitive(true)) },
        )
        vm.nameWork("Refunds", "PAY-7").join()
        runCurrent()
        assertEquals("That key is a ticket", vm.state.value.error?.title)
        assertFalse("work_link" in vm.state.value.error!!.body)

        actions.fail = HubError.Tool("E_EXISTS", "work key BILL-1 already names local work: link the session to it")
        vm.nameWork("Billing", "BILL-1").join()
        runCurrent()
        assertEquals("That key is taken", vm.state.value.error?.title)

        actions.fail = HubError.Tool("E_INVALID", "work title must not be empty")
        vm.nameWork("Billing").join()
        runCurrent()
        assertEquals("The hub refused that name", vm.state.value.error?.title)
        assertEquals("work title must not be empty", vm.state.value.error?.body)

        actions.fail = HubError.Tool("E_NOTFOUND", "session 5 not found")
        vm.nameWork("Billing").join()
        runCurrent()
        assertEquals("This session is gone", vm.state.value.error?.title)

        actions.fail = HubError.Tool("E_FORBIDDEN", "not for this token")
        vm.nameWork("Billing").join()
        runCurrent()
        assertEquals("This device can't name work", vm.state.value.error?.title)
        assertTrue(vm.state.value.canNameWork, "a refusal is not a missing action")
    }

    @Test
    fun a_refused_rename_says_why() {
        val tracker = friendlyName(HubError.Tool("E_INVALID", "work item 70 is a tracker's ticket; only local work can be renamed"), renaming = true)
        assertEquals("Only named work can be renamed", tracker.title)
        assertEquals("That work is gone", friendlyName(HubError.Tool("E_NOTFOUND", "work item 300 not found"), renaming = true).title)
        assertEquals(
            "The hub refused that name",
            friendlyName(HubError.Tool("E_INVALID", "work title longer than 120 characters"), renaming = true).title,
        )
        val other = friendlyName(HubError.Tool("E_CONFIRM_REQUIRED", "approve"), renaming = false)
        assertEquals("Needs a confirmation on the desktop", other.title)
        assertNotNull(other.details)
    }

    /** An "unknown action" refusal hides Name this work… for the connection, and nothing else. */
    @Test
    fun an_unknown_name_hides_only_naming() = runTest {
        val fleet = WorkFleet(listOf(row()), caps = naming)
        val actions = FakeWorkActions().apply { fail = HubError.Tool("E_INVALID", "unknown work_link action \"name\"; one of link") }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)

        vm.nameWork("Billing").join()
        runCurrent()

        assertFalse(vm.state.value.canNameWork)
        assertTrue(vm.state.value.canSetWork, "Set work… stays")
        assertEquals("This hub can't do that yet", vm.state.value.error?.title)
        vm.nameWork("Billing").join()
        runCurrent()
        assertEquals(1, actions.calls.size, "never asked twice on the same connection")
    }
}
