package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionDetailsActions
import dev.claudefleet.mobile.model.FleetTask
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionEvent
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = 7L

private class Fleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(listOf(SessionRow(id = ME, tmuxName = "me", hostAlias = "pine")))
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Details : SessionDetailsActions {
    var historyReads = 0
    var events = listOf(SessionEvent(id = 1, kind = "turn_done"), SessionEvent(id = 2, kind = "stuck"))
    var related = listOf(SessionRow(id = ME, tmuxName = "me"), SessionRow(id = 8, tmuxName = "sibling"))
    var tasks = listOf(
        FleetTask(id = 1, requesterSessionId = ME, workerSessionId = 9, state = "running"),
        FleetTask(id = 2, requesterSessionId = 3, workerSessionId = 4, state = "done"),
        FleetTask(id = 3, requesterSessionId = 5, workerSessionId = ME, state = "done"),
    )
    var historyFails: Throwable? = null
    val cancelled = mutableListOf<Long>()

    override suspend fun history(sessionId: Long): List<SessionEvent> {
        historyReads += 1
        historyFails?.let { throw it }
        return events
    }
    override suspend fun related(sessionId: Long): List<SessionRow> = related
    override suspend fun tasks(): List<FleetTask> = tasks
    override suspend fun cancelTask(taskId: Long) {
        cancelled += taskId
        tasks = tasks.map { if (it.id == taskId) it.copy(state = "cancelled") else it }
    }
}

private val ALL = setOf(
    HubCapabilities.SESSION_HISTORY,
    HubCapabilities.RELATED_SESSIONS,
    HubCapabilities.LIST_TASKS,
    HubCapabilities.CANCEL_TASK,
)

class SessionDetailsViewModelTest {

    @Test
    fun opening_reads_every_section_the_hub_has() = runTest {
        val actions = Details()
        val vm = SessionDetailsViewModel(ME, Fleet(ALL), actions, backgroundScope, canWrite = true) { 0 }

        vm.open().join()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.open)
        assertEquals(listOf(1L, 2L), s.events.map { it.id })
        // Not the session itself, and only tasks it is party to.
        assertEquals(listOf(8L), s.related.map { it.id })
        assertEquals(listOf(1L, 3L), s.tasks.map { it.id })
        assertFalse(s.loading)
    }

    @Test
    fun a_hub_without_the_tools_is_never_asked() = runTest {
        val actions = Details()
        val vm = SessionDetailsViewModel(ME, Fleet(emptySet()), actions, backgroundScope, canWrite = true) { 0 }

        vm.open().join()
        runCurrent()

        assertEquals(0, actions.historyReads)
        assertFalse(vm.state.value.historyAvailable)
    }

    @Test
    fun a_failed_section_says_so_and_leaves_the_others() = runTest {
        val actions = Details()
        actions.historyFails = HubError.Tool("E_INTERNAL", "boom")
        val vm = SessionDetailsViewModel(ME, Fleet(ALL), actions, backgroundScope, canWrite = true) { 0 }

        vm.open().join()
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertEquals(listOf(8L), vm.state.value.related.map { it.id })
    }

    @Test
    fun the_filter_narrows_the_timeline_and_clears_back_to_everything() = runTest {
        val vm = SessionDetailsViewModel(ME, Fleet(ALL), Details(), backgroundScope, canWrite = true) { 0 }
        vm.open().join()
        runCurrent()

        vm.toggle(EventCategory.Errors)
        runCurrent()
        assertEquals(listOf(2L), vm.state.value.shownEvents.map { it.id })

        vm.toggle(EventCategory.Errors)
        runCurrent()
        assertEquals(2, vm.state.value.shownEvents.size)
    }

    @Test
    fun a_row_change_rereads_the_timeline_only_while_open() = runTest {
        val actions = Details()
        val fleet = Fleet(ALL)
        val vm = SessionDetailsViewModel(ME, fleet, actions, backgroundScope, canWrite = true) { 0 }
        runCurrent()

        fleet.sessionChanges.emit(ME)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(0, actions.historyReads)

        vm.open().join()
        runCurrent()
        val afterOpen = actions.historyReads
        fleet.sessionChanges.emit(ME)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(afterOpen + 1, actions.historyReads)
    }

    @Test
    fun cancel_needs_a_token_that_may_write() = runTest {
        val readonly = Details()
        val ro = SessionDetailsViewModel(ME, Fleet(ALL), readonly, backgroundScope, canWrite = false) { 0 }
        ro.open().join()
        runCurrent()
        assertFalse(ro.state.value.canCancel)
        ro.cancel(1).join()
        assertTrue(readonly.cancelled.isEmpty())

        val full = Details()
        val vm = SessionDetailsViewModel(ME, Fleet(ALL), full, backgroundScope, canWrite = true) { 0 }
        vm.open().join()
        runCurrent()
        vm.cancel(1).join()
        runCurrent()
        assertEquals(listOf(1L), full.cancelled)
        assertEquals("cancelled", vm.state.value.tasks.first { it.id == 1L }.state)
        assertNull(vm.state.value.cancelling)
    }
}
