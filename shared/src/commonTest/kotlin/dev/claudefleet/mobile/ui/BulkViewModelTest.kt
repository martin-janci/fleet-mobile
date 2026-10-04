package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.model.WaitResult
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class BulkFleet(rows: List<SessionRow>) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override suspend fun refresh() = Unit
}

/** Only send and kill are bulk calls; anything else would be a bug. */
private class BulkCalls : SessionActions {
    val sent = mutableListOf<Pair<Long, String>>()
    val killed = mutableListOf<Long>()
    val failing = mutableSetOf<Long>()

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult {
        if (sessionId in failing) throw HubError.Tool("E_INVALID_STATE", "busy")
        sent += sessionId to text
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 1)
    }
    override suspend fun kill(sessionId: Long) {
        if (sessionId in failing) throw HubError.Tool("E_SSH", "unreachable")
        killed += sessionId
    }
    override suspend fun conversation(sessionId: Long, turns: Int?, sinceTurn: Long?, claudeSessionId: String?): Conversation = error("unused")
    override suspend fun conversations(sessionId: Long): List<ConversationSummary> = error("unused")
    override suspend fun toolDetail(sessionId: Long, toolUseId: String, claudeSessionId: String?): ToolDetail = error("unused")
    override suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult = error("unused")
    override suspend fun activity(sessionId: Long): ActivityProbe = error("unused")
    override suspend fun capture(sessionId: Long, maxLines: Int): String = error("unused")
    override suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int): WaitResult = error("unused")
    override suspend fun restart(sessionId: Long) = error("unused")
    override suspend fun rewind(sessionId: Long, anchorUuid: String?, mode: String, newWorktree: String?): SessionRow = error("unused")
    override suspend fun safeKill(sessionId: Long) = error("unused")
    override suspend fun setTags(sessionId: Long, tags: List<String>) = error("unused")
    override suspend fun rename(sessionId: Long, friendlyName: String) = error("unused")
    override suspend fun ping(): Boolean = error("unused")
}

private val ROWS = listOf(
    SessionRow(id = 1, tmuxName = "one"),
    SessionRow(id = 2, tmuxName = "two"),
    SessionRow(id = 3, tmuxName = "boss", isController = true),
    SessionRow(id = 4, tmuxName = "outside", kind = "external"),
)

class BulkViewModelTest {

    @Test
    fun a_prompt_goes_to_every_picked_session_and_the_selection_clears() = runTest {
        val calls = BulkCalls()
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = true)
        vm.toggle(1)
        vm.toggle(2)
        runCurrent()
        assertTrue(vm.state.value.active)

        vm.send("go on").join()
        runCurrent()

        assertEquals(listOf(1L to "go on", 2L to "go on"), calls.sent)
        assertFalse(vm.state.value.active)
        assertTrue(vm.state.value.outcome!!.all { it.ok })
    }

    @Test
    fun a_failure_is_named_and_stays_picked() = runTest {
        val calls = BulkCalls()
        calls.failing += 2
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = true)
        vm.toggle(1)
        vm.toggle(2)

        vm.send("go on").join()
        runCurrent()

        assertEquals(listOf(1L to "go on"), calls.sent)
        assertEquals(setOf(2L), vm.state.value.selected)
        assertFalse(vm.state.value.outcome!!.single { it.sessionId == 2L }.ok)
    }

    @Test
    fun kill_skips_what_the_hub_would_refuse_and_says_why() = runTest {
        val calls = BulkCalls()
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = true)
        listOf(1L, 3L, 4L).forEach(vm::toggle)
        runCurrent()
        assertEquals(1, vm.state.value.killable)

        vm.kill().join()
        runCurrent()

        assertEquals(listOf(1L), calls.killed)
        val skipped = vm.state.value.outcome!!.filter { !it.ok }
        assertEquals(setOf(3L, 4L), skipped.map { it.sessionId }.toSet())
        assertTrue(skipped.all { it.reason != null })
    }

    @Test
    fun a_readonly_pairing_picks_nothing() = runTest {
        val calls = BulkCalls()
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = false)
        vm.toggle(1)
        vm.send("x").join()
        runCurrent()
        assertFalse(vm.state.value.active)
        assertTrue(calls.sent.isEmpty())
    }

    @Test
    fun a_session_that_leaves_the_fleet_leaves_the_selection() = runTest {
        val fleet = BulkFleet(ROWS)
        val vm = BulkViewModel(fleet, BulkCalls(), backgroundScope, canWrite = true)
        vm.toggle(1)
        vm.toggle(2)
        fleet.sessions.value = ROWS.filter { it.id != 2L }
        runCurrent()
        assertEquals(setOf(1L), vm.state.value.selected)
        vm.dismissOutcome()
        assertNull(vm.state.value.outcome)
    }
}
