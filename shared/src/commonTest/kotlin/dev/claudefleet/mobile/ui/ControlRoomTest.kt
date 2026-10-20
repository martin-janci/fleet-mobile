package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.model.WaitResult
import dev.claudefleet.mobile.net.HubCapabilities
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
import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.data.ControlActions
import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.ControlHandoff
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.OperatorStatus

private class RoomFleet(rows: List<SessionRow>) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(
        HubCapabilities(tools = setOf(HubCapabilities.ENSURE_OPERATOR, HubCapabilities.OPERATOR_STATUS)),
    )
    override suspend fun refresh() = Unit
}

private class RoomControl(var status: OperatorStatus) : ControlActions, AgentActions {
    var statusReads = 0
    override suspend fun status(): OperatorStatus { statusReads++; return status }
    override suspend fun confirms(): List<ConfirmRequest> = emptyList()
    override suspend fun answer(nonce: String, approved: Boolean): Boolean = true
    override suspend fun handoffs(limit: Int): List<ControlHandoff> = emptyList()
    override suspend fun ensureOperator(): SessionRow = SessionRow(id = 5)
}

private class SteerCalls : SessionActions {
    val sent = mutableListOf<Pair<Long, String>>()
    var failing = false
    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult {
        if (failing) throw HubError.Tool("E_INVALID_STATE", "busy")
        sent += sessionId to text
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 1)
    }
    override suspend fun kill(sessionId: Long) = error("unused")
    override suspend fun conversation(sessionId: Long, turns: Int?, sinceTurn: Long?, claudeSessionId: String?): Conversation = error("unused")
    override suspend fun conversations(sessionId: Long): List<ConversationSummary> = error("unused")
    override suspend fun toolDetail(sessionId: Long, toolUseId: String, claudeSessionId: String?): ToolDetail = error("unused")
    override suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult = error("unused")
    override suspend fun activity(sessionId: Long): ActivityProbe = error("unused")
    override suspend fun capture(sessionId: Long, maxLines: Int): String = error("unused")
    override suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int): WaitResult = error("unused")
    override suspend fun restart(sessionId: Long) = error("unused")
    override suspend fun rewind(sessionId: Long, anchorUuid: String?, mode: String, newWorktree: String?): SessionRow = error("unused")
    override suspend fun spawnReview(sessionId: Long, prompt: String): SessionRow = error("unused")
    override suspend fun repair(sessionId: Long): RepairReport = error("unused")
    override suspend fun recreate(sessionId: Long): SessionRow = error("unused")
    override suspend fun dismissGhost(sessionId: Long) = error("unused")
    override suspend fun safeKill(sessionId: Long) = error("unused")
    override suspend fun setTags(sessionId: Long, tags: List<String>) = error("unused")
    override suspend fun rename(sessionId: Long, friendlyName: String) = error("unused")
    override suspend fun ping(): Boolean = error("unused")
}

/** Control and Missions as Thread rooms on the phone (claude-fleet #913, #914, #921). */
class ControlRoomTest {

    /** Control's session lost under the tab (its agent killed): the tab stops showing it and asks again. */
    @Test
    fun a_lost_control_session_is_noticed_and_its_status_read_again() = runTest {
        val fleet = RoomFleet(listOf(SessionRow(id = 5, tmuxName = "control")))
        val control = RoomControl(OperatorStatus(ready = true, session = SessionRow(id = 5)))
        val vm = ControlViewModel(fleet, control, control, backgroundScope, canWrite = true, pollMs = 100)
        vm.attach()
        runCurrent()
        assertEquals(5L, vm.state.value.sessionId)
        val reads = control.statusReads

        control.status = OperatorStatus(ready = false, blocked = "lost")
        fleet.sessions.value = listOf(SessionRow(id = 5, tmuxName = "control", lostAt = 100, lostReason = "process_died"))
        runCurrent()
        assertNull(vm.state.value.sessionId)
        assertEquals("lost", vm.state.value.blocked)
        assertTrue(control.statusReads > reads)
        assertTrue(controlBlockedLine("lost", null).contains("restarts it by itself"))
        vm.detach()
    }

    @Test
    fun a_steer_goes_to_control_with_the_mission_named_and_says_why_it_cannot() = runTest {
        val fleet = RoomFleet(listOf(SessionRow(id = 5, tmuxName = "control")))
        val calls = SteerCalls()
        var operator: Long? = 5
        val steer = MissionSteer(fleet, calls, backgroundScope, canWrite = true) { operator }
        val m = Mission(id = 9, name = "Ship it")

        steer.send(m, "  skip the docs task  ")?.join()
        assertEquals(listOf(5L to "About mission #9 \"Ship it\":\n\nskip the docs task"), calls.sent)
        assertEquals(listOf("skip the docs task"), steer.state.value.receipts.map { it.text })

        calls.failing = true
        steer.send(m, "again")?.join()
        assertTrue(steer.state.value.error != null)
        assertEquals(1, steer.state.value.receipts.size)

        operator = null
        assertNull(steer.send(m, "anything"))
        assertTrue(steer.blocked(fleet.sessions.value, fleet.status.value)!!.startsWith("Control's agent isn't running"))
        assertEquals("This phone is paired read-only, so it cannot steer.", steerBlocked(SessionRow(id = 5), connected = true, canWrite = false))
        assertTrue(steerBlocked(SessionRow(id = 5, lostAt = 1), connected = true, canWrite = true)!!.contains("was lost"))
        assertTrue(steerBlocked(SessionRow(id = 5), connected = false, canWrite = true)!!.startsWith("Not connected"))
        assertFalse(steerBlocked(SessionRow(id = 5), connected = true, canWrite = true) != null)
    }

    /** Review w21-15: past the 20 receipts kept, a steer that went through still counts, so the box clears. */
    @Test
    fun every_delivered_steer_counts_past_the_receipt_cap() = runTest {
        val fleet = RoomFleet(listOf(SessionRow(id = 5, tmuxName = "control")))
        val calls = SteerCalls()
        val steer = MissionSteer(fleet, calls, backgroundScope, canWrite = true) { 5L }
        val m = Mission(id = 9, name = "Ship it")
        repeat(21) { steer.send(m, "step $it")?.join() }
        assertEquals(20, steer.state.value.receipts.size)
        assertEquals(21, steer.state.value.delivered[9L])
        calls.failing = true
        steer.send(m, "refused")?.join()
        assertEquals(21, steer.state.value.delivered[9L], "a refusal is not delivered")
    }
}
