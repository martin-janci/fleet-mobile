@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID = 42L

private fun row(
    status: String? = "working",
    stuck: String? = null,
    activity: String? = null,
) = SessionRow(
    id = ID,
    tmuxName = "fleet-api",
    friendlyName = "API work",
    hostAlias = "trn",
    projectId = 1,
    claudeStatus = status,
    stuckKind = stuck,
    currentActivity = activity,
)

private fun turn(at: String, prompt: String, vararg items: ConvItem) =
    ConvTurn(prompt = prompt, at = at, endedAt = at, items = items.toList())

private fun text(s: String) = ConvItem.Text(s)

private class FakeFleetState(rows: List<SessionRow> = listOf(row())) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "trn", reachable = true)))
    override val projects = MutableStateFlow(listOf(ProjectRow(id = 1, owner = "o", repo = "r")))
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override suspend fun refresh() = Unit
}

/**
 * The two calls a session screen may make, with the test holding both ends.
 *
 * Deliberately not a `HubClient`: the screens are given [SessionActions] so that
 * every authenticated call goes through `AppSession.withClient` and its 401 rule
 * in the real app, and so a test needs no transport at all.
 */
private class FakeActions : SessionActions {
    var reads = 0
        private set
    val prompts = mutableListOf<String>()

    /** What the next read answers. */
    var answer: Conversation = Conversation()

    /** Held open, a call stays in flight so the disabled box can be observed. */
    var sendGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var sendFails: Throwable? = null
    var readFails: Throwable? = null

    override suspend fun conversation(sessionId: Long, turns: Int?): Conversation {
        reads += 1
        readGate?.await()
        readFails?.let { throw it }
        return answer
    }

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult {
        prompts += text
        sendGate?.await()
        sendFails?.let { throw it }
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 3)
    }
}

class SessionViewModelTest {

    @Test
    fun the_conversation_loads_when_the_screen_opens() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "do it", text("done"))))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.load().join()
        runCurrent()

        assertEquals(1, actions.reads)
        assertEquals(listOf("do it"), vm.state.value.conversation.turns.map { it.prompt })
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun the_screen_says_it_is_loading_until_the_first_read_answers() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loaded)

        val job = vm.load()
        runCurrent()
        assertTrue(vm.state.value.loading, "the screen must be able to say it is fetching")

        gate.complete(Unit)
        job.join()
        runCurrent()

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.loaded, "an answered read is loaded even when it is empty")
    }

    @Test
    fun a_refresh_appends_the_new_turns_and_repeats_none() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        actions.answer = Conversation(listOf(turn("t2", "b"), turn("t3", "c")))
        vm.refresh().join()
        runCurrent()

        assertEquals(listOf("t1", "t2", "t3"), vm.state.value.conversation.turns.map { it.at })
        assertEquals(2, actions.reads)
    }

    @Test
    fun a_read_that_fails_keeps_the_conversation_and_says_why() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        actions.readFails = HubError.Tool("E_NOTFOUND", "session 42 is gone")
        vm.refresh().join()
        runCurrent()

        assertEquals(listOf("t1"), vm.state.value.conversation.turns.map { it.at })
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error)
    }

    @Test
    fun the_prompt_box_is_disabled_while_the_prompt_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        assertTrue(vm.state.value.canSend)

        val job = vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending)
        assertFalse(vm.state.value.canSend, "the box must refuse a second prompt while one is in flight")

        gate.complete(Unit)
        job.join()
        runCurrent()
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun a_delivered_prompt_clears_the_box_and_refetches_the_conversation() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")

        vm.send().join()
        runCurrent()

        assertEquals(listOf("ship it"), actions.prompts)
        assertEquals("", vm.state.value.draft)
        assertNull(vm.state.value.error)
        assertEquals(1, actions.reads, "a delivered prompt should pull the reply in")
    }

    @Test
    fun a_tool_refusal_is_shown_with_its_code_and_the_draft_is_kept() = runTest {
        val actions = FakeActions()
        actions.sendFails = HubError.Tool("E_BUSY", "the session is mid-turn")
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")

        vm.send().join()
        runCurrent()

        assertEquals("E_BUSY: the session is mid-turn", vm.state.value.error)
        assertEquals("ship it", vm.state.value.draft, "a refused prompt must not be thrown away")
        assertFalse(vm.state.value.sending)
    }

    /** `HubError.Transport` says nothing about its cause; the screen still has to say something. */
    @Test
    fun a_transport_failure_is_shown_too() = runTest {
        val actions = FakeActions()
        actions.sendFails = HubError.Transport(IllegalStateException("socket"))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("hello")

        vm.send().join()
        runCurrent()

        val error = vm.state.value.error
        assertTrue(error != null && error.isNotBlank())
        assertFalse(error.contains("socket"), "the cause's text never reaches a person")
    }

    @Test
    fun a_blank_prompt_is_never_sent() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("   ")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()

        assertTrue(actions.prompts.isEmpty())
    }

    /**
     * `send_prompt` is not in the hub's readonly allow-list, so a readonly
     * client token would be refused. The app does not make the call at all —
     * the constraint is that it only ever calls tools its token may use.
     */
    @Test
    fun a_readonly_credential_cannot_send_and_does_not_try() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope, canSendPrompts = false)
        vm.onDraftChange("ship it")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()

        assertTrue(actions.prompts.isEmpty())
        assertTrue(vm.state.value.readOnly)
    }

    @Test
    fun the_bar_follows_the_live_row() = runTest {
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        assertEquals("working", vm.state.value.session?.claudeStatus)
        assertEquals("trn", vm.state.value.session?.hostAlias)

        fleet.sessions.value = listOf(row(status = "blocked", stuck = "press_enter", activity = "waiting"))
        runCurrent()

        assertEquals("blocked", vm.state.value.session?.claudeStatus)
        assertEquals("press_enter", vm.state.value.session?.stuckKind)
        assertEquals("waiting", vm.state.value.session?.currentActivity)
    }

    /** A session killed from the desktop disappears from the fleet under the screen. */
    @Test
    fun a_session_that_leaves_the_fleet_leaves_the_bar_empty_rather_than_stale() = runTest {
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)

        fleet.sessions.value = emptyList()
        runCurrent()

        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun a_tool_call_one_liner_keeps_its_failure_marker() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(
            listOf(
                turn(
                    "t1",
                    "build it",
                    ConvItem.Tool(summary = "Bash(cargo test)", error = true),
                    text("that failed"),
                ),
            ),
        )
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.load().join()
        runCurrent()

        val items = vm.state.value.conversation.turns.single().items
        assertTrue((items.first() as ConvItem.Tool).error)
    }
}
