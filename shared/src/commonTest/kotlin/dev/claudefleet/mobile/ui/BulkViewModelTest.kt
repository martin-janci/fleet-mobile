package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.ConversationSummary
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.QueuePromptResult
import dev.claudefleet.mobile.model.RepairReport
import dev.claudefleet.mobile.model.SendLaterTiming
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

private class BulkFleet(rows: List<SessionRow>) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities())
    override val access = MutableStateFlow(MyAccess.UNKNOWN)
    override suspend fun refresh() = Unit
}

/** Only send and kill are bulk calls; anything else would be a bug. */
private class BulkCalls : SessionActions {
    val sent = mutableListOf<Pair<Long, String>>()
    val killed = mutableListOf<Long>()
    val failing = mutableSetOf<Long>()
    val queued = mutableListOf<Pair<Long, String>>()
    /** Sessions `queue_prompt` types into at once (they were idle). */
    val idle = mutableSetOf<Long>()

    override suspend fun queuePrompt(sessionId: Long, prompt: String, timing: SendLaterTiming): QueuePromptResult {
        if (sessionId in failing) throw HubError.Tool("E_INVALID_STATE", "busy")
        queued += sessionId to prompt
        return QueuePromptResult(sessionId, delivered = sessionId in idle, queuedId = if (sessionId in idle) null else sessionId)
    }

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
    override suspend fun spawnReview(sessionId: Long, prompt: String): SessionRow = error("unused")
    override suspend fun repair(sessionId: Long): RepairReport = error("unused")
    override suspend fun recreate(sessionId: Long): SessionRow = error("unused")
    override suspend fun dismissGhost(sessionId: Long) = error("unused")
    override suspend fun safeKill(sessionId: Long) = error("unused")
    override suspend fun setTags(sessionId: Long, tags: List<String>) = error("unused")
    override suspend fun rename(sessionId: Long, friendlyName: String) = error("unused")
    override suspend fun ping(): Boolean = error("unused")
}

private val ROWS = listOf(
    SessionRow(id = 1, tmuxName = "one", status = "running"),
    SessionRow(id = 2, tmuxName = "two", status = "running"),
    SessionRow(id = 3, tmuxName = "boss", status = "running", isController = true),
    SessionRow(id = 4, tmuxName = "outside", status = "running", kind = "external"),
)

class BulkViewModelTest {

    /** r09 B6: Archive is offered only where the hub archives, and skips the controller. */
    @Test
    fun archive_is_offered_where_the_hub_archives_and_skips_the_controller() = runTest {
        val fleet = BulkFleet(ROWS)
        val archived = mutableListOf<Long>()
        val vm = BulkViewModel(fleet, BulkCalls(), backgroundScope, canWrite = true, archiveOne = { archived += it })
        runCurrent()
        assertFalse(vm.state.value.canArchive, "an old hub has no archive")

        fleet.capabilities.value = HubCapabilities(tools = setOf(HubCapabilities.WORK_LINK))
        vm.toggle(1)
        vm.toggle(3)
        runCurrent()
        assertTrue(vm.state.value.canArchive)
        vm.archive().join()
        runCurrent()
        assertEquals(listOf(1L), archived)
        assertEquals(BulkAction.Archive, vm.state.value.action)
        assertEquals(listOf(true, false), vm.state.value.outcome?.map { it.ok })
    }

    @Test
    fun select_mode_can_start_with_nothing_picked_and_ends_on_clear() = runTest {
        val vm = BulkViewModel(BulkFleet(ROWS), BulkCalls(), backgroundScope, canWrite = true)
        vm.start()
        runCurrent()
        assertTrue(vm.state.value.active)
        assertTrue(vm.state.value.selected.isEmpty())

        vm.clear()
        runCurrent()
        assertFalse(vm.state.value.active)
    }

    @Test
    fun a_readonly_pairing_never_enters_select_mode() = runTest {
        val vm = BulkViewModel(BulkFleet(ROWS), BulkCalls(), backgroundScope, canWrite = false)
        vm.start()
        runCurrent()
        assertFalse(vm.state.value.active)
    }

    @Test
    fun a_finished_run_leaves_select_mode_with_its_selection() = runTest {
        val vm = BulkViewModel(BulkFleet(ROWS), BulkCalls(), backgroundScope, canWrite = true)
        vm.start()
        vm.toggle(1)
        vm.send("go on").join()
        runCurrent()
        assertFalse(vm.state.value.active)
    }

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

    /** Redesign 14.3: Retry for one session repeats the last action there only. */
    @Test
    fun retry_one_repeats_the_last_action_for_that_session_only() = runTest {
        val calls = BulkCalls()
        calls.failing += setOf(1L, 2L)
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = true)
        vm.toggle(1)
        vm.toggle(2)
        vm.send("rebase").join()
        runCurrent()
        assertEquals(BulkAction.Send("rebase"), vm.state.value.action)
        assertEquals(setOf(1L, 2L), vm.state.value.retryable.map { it.sessionId }.toSet())

        calls.failing.clear()
        vm.retry(2).join()
        runCurrent()

        assertEquals(listOf(2L to "rebase"), calls.sent)
        val outcome = vm.state.value.outcome!!
        assertEquals(listOf(1L, 2L), outcome.map { it.sessionId }, "the outcome keeps every line, in order")
        assertTrue(outcome.single { it.sessionId == 2L }.ok)
        assertFalse(outcome.single { it.sessionId == 1L }.ok)
        assertEquals(setOf(1L), vm.state.value.selected)
    }

    @Test
    fun retry_all_reaches_every_failure_and_never_a_skip() = runTest {
        val calls = BulkCalls()
        calls.failing += 1L
        val vm = BulkViewModel(BulkFleet(ROWS), calls, backgroundScope, canWrite = true)
        listOf(1L, 2L, 3L).forEach(vm::toggle)
        vm.kill().join()
        runCurrent()
        assertEquals(listOf(2L), calls.killed)
        assertEquals(listOf(1L), vm.state.value.retryable.map { it.sessionId }, "the controller's skip is not retryable")

        calls.failing.clear()
        vm.retryFailed().join()
        runCurrent()

        assertEquals(listOf(2L, 1L), calls.killed)
        assertTrue(vm.state.value.retryable.isEmpty())
        assertFalse(vm.state.value.outcome!!.single { it.sessionId == 3L }.ok, "the skip is still reported")
    }

    /** Review w22-1: the desktop's bulk prompt queues, and skips shells, stopped rows and narrow shares. */
    @Test
    fun a_bulk_prompt_queues_where_the_hub_can_and_skips_what_cannot_take_it() = runTest {
        val rows = ROWS.take(2) + listOf(
            SessionRow(id = 5, tmuxName = "shell-1", status = "running", kind = "shell"),
            SessionRow(id = 6, tmuxName = "gone", status = "stopped"),
            SessionRow(id = 7, tmuxName = "theirs", status = "running", ownerPersonId = 9),
        )
        val fleet = BulkFleet(rows)
        fleet.capabilities.value = HubCapabilities(tools = setOf(HubCapabilities.QUEUE_PROMPT, HubCapabilities.QUEUED_PROMPTS))
        fleet.access.value = MyAccess(personId = 1, grants = mapOf(7L to GrantLevel.WATCH))
        val calls = BulkCalls().apply { idle += 1L }
        val vm = BulkViewModel(fleet, calls, backgroundScope, canWrite = true)
        listOf(1L, 2L, 5L, 6L, 7L).forEach(vm::toggle)

        vm.send("rebase on main").join()
        runCurrent()

        assertTrue(calls.sent.isEmpty(), "never typed straight in where the hub queues")
        assertEquals(listOf(1L to "rebase on main", 2L to "rebase on main"), calls.queued)
        val outcome = vm.state.value.outcome!!
        assertNull(outcome.single { it.sessionId == 1L }.reason, "an idle session took it now")
        assertEquals("Queued · goes in when idle", outcome.single { it.sessionId == 2L }.reason)
        val skipped = outcome.filter { !it.ok }
        assertEquals(setOf(5L, 6L, 7L), skipped.map { it.sessionId }.toSet())
        assertTrue(skipped.none { it.retryable })
    }

    /** Review w22-8: offline, nothing goes out; review w24: and the outcome says so. */
    @Test
    fun offline_the_bar_is_off_and_nothing_goes_out() = runTest {
        val fleet = BulkFleet(ROWS)
        fleet.status.value = ConnectionStatus.Offline("no signal")
        val calls = BulkCalls()
        val vm = BulkViewModel(fleet, calls, backgroundScope, canWrite = true)
        vm.toggle(1)
        runCurrent()
        assertFalse(vm.state.value.online)
        vm.kill().join()
        runCurrent()
        assertTrue(calls.killed.isEmpty())
        // Not silently (review w24): the sheet has closed, so each picked row
        // says it did not go, and Retry stays for when the hub is back.
        val outcome = vm.state.value.outcome!!.single()
        assertEquals(1L, outcome.sessionId)
        assertFalse(outcome.ok)
        assertEquals(NOT_CONNECTED, outcome.reason)
        assertTrue(outcome.retryable)
        assertEquals(BulkAction.Kill, vm.state.value.action)
    }

    @Test
    fun a_pinned_session_is_never_bulk_killed() {
        assertEquals(
            "a pinned session is not killed; unpin it first",
            killRefusal(SessionRow(id = 5, tmuxName = "kept", pinnedAt = 100)),
        )
        assertNull(killRefusal(SessionRow(id = 1, tmuxName = "one")))
    }
}
