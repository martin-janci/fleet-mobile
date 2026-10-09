package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.ControlActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.ConfirmRequest
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
    var confirms = listOf(
        ConfirmRequest(nonce = "n1", tool = "kill_session", summary = "Stop pine/api", operator = true),
        ConfirmRequest(nonce = "n2", tool = "merge_pr", summary = "Merge #12", caller = "ci-bot"),
    )

    override suspend fun status(): OperatorStatus { statusReads++; return status }
    override suspend fun confirms(): List<ConfirmRequest> { confirmReads++; return confirms }
    override suspend fun answer(nonce: String, approved: Boolean): Boolean {
        answers += nonce to approved
        confirms = confirms.filterNot { it.nonce == nonce }
        return true
    }
    override suspend fun ensureOperator(): SessionRow {
        wakes++
        status = OperatorStatus(ready = true, session = SessionRow(id = 77))
        return SessionRow(id = 77)
    }
}

class ControlChatTest {

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
    fun each_blocked_state_says_what_to_do() {
        assertTrue("Wake" in controlBlockedLine(null, null))
        assertTrue("Settings › Devices" in controlBlockedLine("token_revoked", null))
        assertTrue("(pine)" in controlBlockedLine("host_down", "pine"))
        assertTrue("odd" in controlBlockedLine("odd", null))
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
