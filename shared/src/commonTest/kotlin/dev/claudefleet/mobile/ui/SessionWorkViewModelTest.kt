@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.TimelineFrame
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MultiStart
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
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.ui.components.BannerTone
import dev.claudefleet.mobile.ui.components.bannerTone
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

    /** What a multi-start answers; the call is recorded as `start_many KEY host [ids]`. */
    var manyAnswer = MultiStart()

    override suspend fun startMany(key: String, hostAlias: String, projectIds: List<Long>): MultiStart {
        calls += "start_many $key $hostAlias $projectIds"
        fail?.let { throw it }
        return manyAnswer
    }

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

    override suspend fun name(sessionId: Long, title: String, key: String?) = record("name $sessionId $title ${key ?: "-"}")

    override suspend fun renameItem(itemId: Long, title: String) {
        record("rename $itemId $title")
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
        assertEquals(BannerTone.Info, bannerTone(vm.state.value.error), "not an error, but still drawn")
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

    // ---- the ticket card on the session (M10.5) ----

    private fun cardFor(key: String) = TicketCard(key = key, title = "Refund", cached = true, acceptance = listOf("Retries back off"))

    /** Opening the sheet reads the chip's card; a read, so a readonly token gets it too. */
    @Test
    fun the_sheet_shows_the_chips_card_to_any_token() = runTest {
        for (canWrite in listOf(true, false)) {
            val actions = FakeWorkActions().apply { cardAnswer = cardFor("PAY-7") }
            val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7))), actions, backgroundScope, canWrite = canWrite)
            assertNull(vm.state.value.card, "nothing is read before the sheet opens")

            vm.openSheet()?.join()
            runCurrent()

            assertEquals(listOf("PAY-7"), actions.cardCalls)
            assertEquals(listOf("Retries back off"), vm.state.value.card?.acceptance, "canWrite=$canWrite")
            assertEquals(emptyList(), actions.calls, "reading a card decides nothing")
        }
    }

    /**
     * The hub's own answer (recorded wire shape): the sheet's card and its Copy
     * text carry the tracker's words literally — markup as text, a URL in a
     * criterion as text — and never the desktop's `composer_text`.
     */
    @Test
    fun the_hubs_card_is_copied_as_the_trackers_plain_words() = runTest {
        val recorded = json.decodeFromString(TicketCard.serializer(), HubWorkJson.CARD_WITH_AC)
        val actions = FakeWorkActions().apply { cardAnswer = recorded }
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7))), actions, backgroundScope, canWrite = false)
        vm.openSheet()?.join()
        runCurrent()

        val copy = assertNotNull(vm.state.value.card).copyText
        assertEquals(
            "PAY-7 · Refund flow\nStatus: In Progress\nhttps://acme.atlassian.net/browse/PAY-7\n\nAcceptance criteria\n" +
                "- Refund issued within 24 h\n- Email sent to https://evil.example/phish\n- <b>not bold</b>",
            copy,
        )
        assertFalse("claude-fleet:" in copy, "the composer's untrusted-input fence is the desktop's, not the phone's")
    }

    @Test
    fun a_hub_without_card_is_never_asked_and_an_uncached_key_shows_none() = runTest {
        val noCard = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work" to setOf("tickets", "lookup"))))
        val actions = FakeWorkActions().apply { cardAnswer = cardFor("PAY-7") }
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7)), noCard), actions, backgroundScope, canWrite = true)
        vm.openSheet()?.join()
        runCurrent()
        assertEquals(emptyList(), actions.cardCalls)
        assertNull(vm.state.value.card)

        val uncached = FakeWorkActions().apply { cardAnswer = TicketCard(key = "PAY-7", cached = false) }
        val vm2 = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7))), uncached, backgroundScope, canWrite = true)
        vm2.openSheet()?.join()
        runCurrent()
        assertNull(vm2.state.value.card, "a key nobody cached has nothing to show")
    }

    /** A failed read says nothing on the sheet; an unknown action is forgotten for the connection. */
    @Test
    fun a_refused_card_leaves_the_sheet_alone() = runTest {
        val fleet = WorkFleet(listOf(row(work = PAY7)))
        val actions = object : WorkActions by FakeWorkActions() {
            override suspend fun card(key: String): TicketCard = throw HubError.Tool("E_INVALID", "unknown action 'card'")
        }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)
        vm.openSheet()?.join()
        runCurrent()
        assertNull(vm.state.value.card)
        assertNull(vm.state.value.error, "the card is extra: no banner")
        assertFalse(fleet.capabilities.value.has("work", "card"), "the older hub's refusal is remembered")
    }

    /** The card shown is always the chip's: a chip that moved to another ticket drops the old card. */
    @Test
    fun the_card_follows_the_chip() = runTest {
        val fleet = WorkFleet(listOf(row(work = PAY7)))
        val actions = FakeWorkActions().apply { cardAnswer = cardFor("PAY-7") }
        val vm = SessionWorkViewModel(5, fleet, actions, backgroundScope, canWrite = true)
        vm.openSheet()?.join()
        runCurrent()
        assertNotNull(vm.state.value.card)

        fleet.sessions.value = listOf(row(work = PAY7.copy(linkId = 13, itemId = 80, key = "PAY-8")))
        runCurrent()
        assertNull(vm.state.value.card, "PAY-7's card is not PAY-8's")
    }

    // ── naming local work on the phone (claude-fleet M13.4a, D20) ──────────

    private val LOCAL = WorkSummary(linkId = 21, itemId = 42, key = "OPS-1", title = "Ops cleanup", source = "manual", state = "confirmed")

    @Test
    fun a_session_with_no_work_may_be_named_and_one_with_work_may_not() = runTest {
        val actions = FakeWorkActions()
        val bare = SessionWorkViewModel(5, WorkFleet(listOf(row(guess = PAY9_GUESS))), actions, backgroundScope, canWrite = true)
        assertTrue(bare.state.value.canNameWork, "a guess is not work: naming is still offered")
        assertFalse(bare.state.value.canRenameWork)

        val linked = SessionWorkViewModel(5, WorkFleet(listOf(row(work = PAY7))), actions, backgroundScope, canWrite = true)
        assertFalse(linked.state.value.canNameWork)
    }

    @Test
    fun naming_sends_the_trimmed_title_and_the_key_only_when_given() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row())), actions, backgroundScope, canWrite = true)

        vm.nameWork("  Ops cleanup  ", "  ")
        runCurrent()
        vm.nameWork("Ops cleanup", " OPS-1 ")
        runCurrent()

        assertEquals(listOf("name 5 Ops cleanup -", "name 5 Ops cleanup OPS-1"), actions.calls)
    }

    @Test
    fun a_bad_title_is_said_here_and_never_sent() = runTest {
        val actions = FakeWorkActions()
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row())), actions, backgroundScope, canWrite = true)

        vm.nameWork("   ")
        runCurrent()
        assertEquals("Give the work a name", vm.state.value.error?.title)
        vm.nameWork("x".repeat(WORK_TITLE_MAX + 1))
        runCurrent()
        assertEquals("That name is too long", vm.state.value.error?.title)
        vm.nameWork("bad\u0007bell")
        runCurrent()
        assertEquals("That name has an unprintable character", vm.state.value.error?.title)
        vm.nameWork("x".repeat(WORK_TITLE_MAX))
        runCurrent()
        assertEquals(1, actions.calls.size, "only the one within the rules is sent")
    }

    /** The non-negotiable again: a readonly token, or a hub without `name`, is offered neither and calls nothing. */
    @Test
    fun a_readonly_token_or_an_older_hub_cannot_name_or_rename() = runTest {
        val actions = FakeWorkActions()
        val ro = SessionWorkViewModel(5, WorkFleet(listOf(row())), actions, backgroundScope, canWrite = false)
        assertFalse(ro.state.value.canNameWork)
        ro.nameWork("Ops cleanup")
        val roLocal = SessionWorkViewModel(5, WorkFleet(listOf(row(work = LOCAL))), actions, backgroundScope, canWrite = false)
        assertFalse(roLocal.state.value.canRenameWork)
        roLocal.renameWork("Ops")

        val older = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("link", "unlink", "reject", "confirm"))))
        val old = SessionWorkViewModel(5, WorkFleet(listOf(row()), older), actions, backgroundScope, canWrite = true)
        assertFalse(old.state.value.canNameWork, "a hub before M11.1 has no name action")
        old.nameWork("Ops cleanup")
        runCurrent()
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun only_local_work_is_offered_a_rename_and_it_renames_the_item() = runTest {
        val actions = FakeWorkActions()
        val ticket = SessionWorkViewModel(
            5,
            WorkFleet(listOf(row(work = PAY7.copy(statusCategory = null, url = "https://acme.atlassian.net/browse/PAY-7")))),
            actions,
            backgroundScope,
            canWrite = true,
        )
        assertFalse(ticket.state.value.canRenameWork, "a ticket has a URL: not local")
        val bareKey = SessionWorkViewModel(5, WorkFleet(listOf(row(work = LOCAL.copy(itemId = null)))), actions, backgroundScope, canWrite = true)
        assertFalse(bareKey.state.value.canRenameWork, "a bare key has no item to rename")

        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row(work = LOCAL))), actions, backgroundScope, canWrite = true)
        assertTrue(vm.state.value.canRenameWork)
        vm.openSheet()
        runCurrent()
        vm.renameWork("  Ops cleanup, part two ")
        runCurrent()
        assertEquals(listOf("rename 42 Ops cleanup, part two"), actions.calls)
        assertFalse(vm.state.value.sheetOpen, "the sheet closes; the row's own frame shows the new title")
    }

    @Test
    fun a_key_that_is_a_ticket_says_to_set_work_instead() = runTest {
        val ticketTaken = FakeWorkActions().apply {
            fail = HubError.Tool(
                "E_EXISTS",
                "PAY-7 is a ticket",
                kotlinx.serialization.json.buildJsonObject {
                    put("item_id", kotlinx.serialization.json.JsonPrimitive(70))
                    put("tracker", kotlinx.serialization.json.JsonPrimitive(true))
                },
            )
        }
        val vm = SessionWorkViewModel(5, WorkFleet(listOf(row())), ticketTaken, backgroundScope, canWrite = true)
        vm.nameWork("Refund", "PAY-7")
        runCurrent()
        assertEquals("That key is a ticket", vm.state.value.error?.title)

        val localTaken = FakeWorkActions().apply { fail = HubError.Tool("E_EXISTS", "OPS-1 is already local work") }
        val vm2 = SessionWorkViewModel(5, WorkFleet(listOf(row())), localTaken, backgroundScope, canWrite = true)
        vm2.nameWork("Ops", "OPS-1")
        runCurrent()
        assertEquals("That key is taken", vm2.state.value.error?.title)
        assertEquals("OPS-1 is already local work", vm2.state.value.error?.body)
    }
}
