package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.ControlActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.ControlHandoff
import dev.claudefleet.mobile.model.HandoffItem
import dev.claudefleet.mobile.model.FieldProblem
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.OperatorStatus
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.ReplyField
import dev.claudefleet.mobile.model.ReplyForm
import dev.claudefleet.mobile.model.ReplyStep
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CONTROL_TOOLS = setOf(
    HubCapabilities.ENSURE_OPERATOR,
    HubCapabilities.OPERATOR_STATUS,
    HubCapabilities.MCP_CONFIRMS,
    HubCapabilities.ANSWER_MCP_CONFIRM,
    HubCapabilities.CONTROL_HANDOFFS,
)

private class ControlFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class FakeControl(var status: OperatorStatus) : ControlActions, AgentActions {
    var statusReads = 0
    var confirmReads = 0
    var wakes = 0
    val answers = mutableListOf<Pair<String, Boolean>>()
    var recorded = true
    var confirms = listOf(
        ConfirmRequest(nonce = "n1", tool = "kill_session", summary = "Stop pine/api", operator = true),
        ConfirmRequest(nonce = "n2", tool = "merge_pr", summary = "Merge #12", caller = "ci-bot"),
    )

    override suspend fun status(): OperatorStatus { statusReads++; return status }
    override suspend fun confirms(): List<ConfirmRequest> { confirmReads++; return confirms }
    override suspend fun answer(nonce: String, approved: Boolean): Boolean {
        answers += nonce to approved
        confirms = confirms.filterNot { it.nonce == nonce }
        return recorded
    }
    var handoffReads = 0
    var handoffs = listOf<ControlHandoff>()
    override suspend fun handoffs(limit: Int): List<ControlHandoff> { handoffReads++; return handoffs.take(limit) }
    override suspend fun ensureOperator(): SessionRow {
        wakes++
        status = OperatorStatus(ready = true, session = SessionRow(id = 77))
        return SessionRow(id = 77)
    }
}

class ControlChatTest {

    /** Review r13 (P13-7): a failed status read is not "not running"; Retry and a reconnect read it again. */
    @Test
    fun a_failed_status_read_is_said_and_read_again_when_the_hub_returns() = runTest {
        val fleet = ControlFleet(CONTROL_TOOLS)
        val fake = object : ControlActions by FakeControl(OperatorStatus(ready = false, blocked = "absent")) {
            var fail = true
            var reads = 0
            override suspend fun status(): OperatorStatus {
                reads += 1
                if (fail) throw dev.claudefleet.mobile.net.HubError.Transport(IllegalStateException("x"))
                return OperatorStatus(ready = true, session = SessionRow(id = 9))
            }
        }
        val agent = FakeControl(OperatorStatus(ready = false))
        val vm = ControlViewModel(fleet, fake, agent, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertTrue(vm.state.value.statusFailed)
        assertEquals("Couldn't read Control's state.", controlWaitingLine(vm.state.value))
        assertFalse(vm.state.value.canWake)

        // The hub drops and comes back: the status is read again on its own.
        fleet.status.value = ConnectionStatus.Reconnecting(2, "Can't reach the hub from this network.")
        runCurrent()
        assertTrue(controlWaitingLine(vm.state.value).startsWith("Not connected"))
        fake.fail = false
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        assertEquals(2, fake.reads)
        assertFalse(vm.state.value.statusFailed)
        assertEquals(9L, vm.state.value.sessionId)
        vm.detach()
    }

    @Test
    fun a_running_control_opens_its_conversation_in_the_tab_and_wakes_nothing() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5), host = "pine"))
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertEquals(5L, vm.state.value.sessionId)
        assertTrue(vm.state.value.known)
        assertEquals(0, fake.wakes)
    }

    @Test
    fun control_is_woken_only_on_the_tap() = runTest {
        val fake = FakeControl(OperatorStatus(ready = false, blocked = "absent"))
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertNull(vm.state.value.sessionId)
        assertTrue(vm.state.value.canWake)
        assertEquals(0, fake.wakes)

        vm.wake()?.join(); runCurrent()
        runCurrent()
        assertEquals(1, fake.wakes)
        assertEquals(77L, vm.state.value.sessionId)
    }

    @Test
    fun a_revoked_token_or_a_missing_host_is_not_woken() = runTest {
        for (blocked in listOf("token_revoked", "no_host", "host_down")) {
            val fake = FakeControl(OperatorStatus(ready = false, blocked = blocked))
            val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true)
            vm.refresh().join(); runCurrent()
            assertFalse(vm.state.value.canWake, blocked)
            assertNull(vm.wake(), blocked)
            assertEquals(0, fake.wakes, blocked)
        }
    }

    @Test
    fun confirms_are_read_while_the_tab_shows_and_stop_when_it_goes() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertEquals(listOf("n1", "n2"), vm.state.value.confirms.map { it.nonce })
        advanceTimeBy(101)
        runCurrent()
        assertEquals(2, fake.confirmReads)

        vm.detach()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, fake.confirmReads)
    }

    @Test
    fun an_offline_or_stopped_hub_is_not_polled_until_it_is_back() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val fleet = ControlFleet(CONTROL_TOOLS)
        fleet.status.value = ConnectionStatus.Offline("stopped")
        val vm = ControlViewModel(fleet, fake, fake, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, fake.confirmReads)

        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        advanceTimeBy(101)
        runCurrent()
        assertTrue(fake.confirmReads >= 1)
        vm.detach()
    }

    @Test
    fun an_answer_goes_on_the_tap_and_takes_its_card_away() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true)
        vm.refresh().join(); runCurrent()
        assertEquals(emptyList(), fake.answers)

        vm.answer("n2", false)?.join(); runCurrent()
        assertEquals(listOf("n2" to false), fake.answers)
        assertEquals(listOf("n1"), vm.state.value.confirms.map { it.nonce })
        assertNull(vm.answer("missing", true))
    }

    @Test
    fun an_answer_the_hub_did_not_record_is_said() = runTest {
        // The desktop answered first (the hub keeps the first answer), or the
        // request expired: the tap counted for nothing, and the screen says so.
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5))).apply { recorded = false }
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = true)
        vm.refresh().join(); runCurrent()

        vm.answer("n1", true)?.join(); runCurrent()
        assertEquals(listOf("n2"), vm.state.value.confirms.map { it.nonce })
        assertEquals("Already answered or expired", vm.state.value.error?.title)
    }

    @Test
    fun a_hub_without_the_tools_is_never_asked() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val vm = ControlViewModel(ControlFleet(setOf(HubCapabilities.ENSURE_OPERATOR)), fake, fake, backgroundScope, canWrite = true)
        vm.attach()
        runCurrent()
        assertEquals(0, fake.statusReads)
        assertEquals(0, fake.confirmReads)
        assertFalse(vm.state.value.canConfirm)
        assertTrue(vm.state.value.known)
        assertNull(vm.answer("n1", true))
    }

    @Test
    fun a_readonly_pairing_gets_no_control() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val vm = ControlViewModel(ControlFleet(CONTROL_TOOLS), fake, fake, backgroundScope, canWrite = false)
        vm.refresh().join(); runCurrent()
        assertFalse(vm.state.value.available)
        assertNull(vm.state.value.sessionId)
        assertNull(vm.wake())
    }

    @Test
    fun handoffs_are_read_with_the_confirms_and_follow_the_session_live() = runTest {
        val fleet = ControlFleet(CONTROL_TOOLS)
        fleet.sessions.value = listOf(SessionRow(id = 3, tmuxName = "api", claudeStatus = "working"))
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        fake.handoffs = listOf(ControlHandoff(id = 1, kind = "session", tool = "send_prompt", sessionId = 3, preview = "Fix CI"))
        val vm = ControlViewModel(fleet, fake, fake, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertEquals(listOf(HandoffChip(1, "Sent to api", "working", 3)), vm.state.value.handoffs)

        fleet.sessions.value = listOf(SessionRow(id = 3, tmuxName = "api", claudeStatus = "completed"))
        runCurrent()
        assertEquals("done", vm.state.value.handoffs.single().state)
        vm.detach()
    }

    @Test
    fun a_hub_without_receipts_draws_no_chips_and_is_not_asked() = runTest {
        val fake = FakeControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        fake.handoffs = listOf(ControlHandoff(id = 1, kind = "session", sessionId = 3))
        val tools = CONTROL_TOOLS - HubCapabilities.CONTROL_HANDOFFS
        val vm = ControlViewModel(ControlFleet(tools), fake, fake, backgroundScope, canWrite = true)
        vm.refresh().join(); runCurrent()
        assertEquals(0, fake.handoffReads)
        assertEquals(emptyList(), vm.state.value.handoffs)
    }

    @Test
    fun each_kind_of_handoff_reads_as_a_chip() {
        val ended = handoffChip(ControlHandoff(id = 1, kind = "session", sessionId = 9, preview = "Fix CI"), emptyList())
        assertEquals(HandoffChip(1, "Sent to Fix CI · ended", null), ended)
        val mission = handoffChip(ControlHandoff(id = 2, kind = "mission", missionId = 4, missionName = "Release", missionState = "active"), emptyList())
        assertEquals(HandoffChip(2, "Mission Release", "working"), mission)
        val task = handoffChip(ControlHandoff(id = 3, kind = "task", item = HandoffItem(7, "Write docs", "done")), emptyList())
        assertEquals(HandoffChip(3, "Task Write docs", "done"), task)
        val tree = handoffChip(
            ControlHandoff(
                id = 4,
                kind = "tree",
                item = HandoffItem(1, "Ship 2.0", "todo"),
                items = listOf(HandoffItem(2, "a", "todo", "proposed"), HandoffItem(3, "b", "todo", "accepted")),
            ),
            emptyList(),
        )
        assertEquals("Proposed 2 subtasks under Ship 2.0 · 1 to decide on the desktop", tree.label)
        assertEquals("waiting", tree.state)
        assertEquals("failed", handoffSessionState(SessionRow(id = 1, claudeStatus = "working", stuckKind = "oom")))
    }

    @Test
    fun the_newest_three_show_oldest_first() {
        val hs = (5L downTo 1L).map { ControlHandoff(id = it, kind = "task", item = HandoffItem(it, "t$it", "todo")) }
        assertEquals(listOf(3L, 4L, 5L), handoffChips(hs, emptyList()).map { it.id })
    }

    @Test
    fun each_blocked_state_says_what_to_do() {
        assertTrue("Wake" in controlBlockedLine(null, null))
        assertTrue("Settings › Devices" in controlBlockedLine("token_revoked", null))
        assertTrue("(pine)" in controlBlockedLine("host_down", "pine"))
        // The hub's own word is not a sentence: it stays off the screen (r13).
        assertFalse("odd" in controlBlockedLine("odd", null))
        assertEquals("Control cannot take a message right now.", controlBlockedLine("odd", null))
    }
}

private fun field(name: String, type: String = "text", required: Boolean = true) =
    ReplyField(name, type, name.replaceFirstChar { it.uppercase() }, null, required, null, null, null, emptyList(), null, null, false)

private fun step(title: String, vararg fields: ReplyField) = ReplyStep(title, null, null, fields.toList())

class OrbitChatFormTest {

    @Test
    fun one_step_is_inline_short_steps_a_sheet_and_long_or_secret_forms_the_whole_screen() {
        assertEquals(FormSize.Inline, formSize(ReplyForm("A", null, null, listOf(step("One", field("a"))))))
        assertEquals(
            FormSize.Sheet,
            formSize(ReplyForm("A", null, null, listOf(step("One", field("a")), step("Two", field("b"), field("c"))))),
        )
        assertEquals(
            FormSize.Full,
            formSize(ReplyForm("A", null, null, (1..4).map { step("S$it", field("f$it")) })),
        )
        assertEquals(
            FormSize.Full,
            formSize(ReplyForm("A", null, null, listOf(step("One", field("token", type = "secret"))))),
        )
    }

    @Test
    fun the_forward_button_names_what_is_missing_until_the_last_step_sends() {
        val name = field("name")
        assertEquals(name, firstMissing(listOf(name), emptyMap()))
        assertNull(firstMissing(listOf(name), mapOf("name" to JsonPrimitive("api"))))
        assertEquals("Fill in Name", pageButton(name, last = false, submit = null))
        assertEquals("Next", pageButton(null, last = false, submit = "Deploy"))
        assertEquals("Deploy", pageButton(null, last = true, submit = "Deploy"))
        assertEquals("Send answers", pageButton(null, last = true, submit = null))
    }

    @Test
    fun a_refused_answer_goes_back_to_its_page() {
        val form = ReplyForm("A", null, null, listOf(step("One", field("a")), step("Two", field("b"))))
        val pages = formPages(form, emptyMap())
        assertEquals(2, pages.size)
        assertEquals(1, pageOfProblem(pages, listOf(FieldProblem("b", "too long"))))
        assertNull(pageOfProblem(pages, listOf(FieldProblem("zzz", "unknown"))))
    }

    @Test
    fun a_decided_form_folds_to_one_line() {
        val f = FormView(formId = "f", title = "Deploy")
        assertEquals("✓ Deploy · answered by Martin", formOutcomeLine(f.copy(state = "answered", answeredBy = "Martin")))
        assertEquals("✕ Deploy · declined", formOutcomeLine(f.copy(state = "declined")))
        assertEquals("◷ Deploy · expired", formOutcomeLine(f.copy(state = "expired")))
        assertEquals("✕ Deploy · withdrawn by the agent", formOutcomeLine(f.copy(state = "cancelled")))
    }
}
