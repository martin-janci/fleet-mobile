package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MoveActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MoveOutcome
import dev.claudefleet.mobile.model.MovePreview
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.moveOutcomeOf
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private class MoveFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(listOf(SessionRow(id = 3, tmuxName = "s", hostAlias = "pine")))
    override val hosts = MutableStateFlow(
        listOf(HostRow("pine", reachable = true), HostRow("oak", reachable = true), HostRow("down"), HostRow("quiet", reachable = true, hidden = true)),
    )
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Moves : MoveActions {
    val calls = mutableListOf<String>()
    var answer: MoveOutcome = MoveOutcome.Moved(SessionRow(id = 9, tmuxName = "s", hostAlias = "oak"), sourceKilled = true, warnings = emptyList())
    override suspend fun preview(sessionId: Long, targetHost: String, keepSource: Boolean): MoveOutcome {
        calls += "preview $targetHost"
        return MoveOutcome.Preview(MovePreview(toHost = targetHost, branch = "feat/x"))
    }
    override suspend fun move(sessionId: Long, targetHost: String, keepSource: Boolean, whenIdle: Boolean): MoveOutcome {
        calls += "move $targetHost keep=$keepSource idle=$whenIdle"
        return answer
    }
    override suspend fun cancelWait(sessionId: Long, targetHost: String): MoveOutcome {
        calls += "cancel $targetHost"
        return MoveOutcome.WaitCancelled(wasWaiting = true)
    }
}

private val MOVE = setOf(HubCapabilities.MOVE_SESSION)

class MoveViewModelTest {

    @Test
    fun only_reachable_shown_other_hosts_are_targets() = runTest {
        val vm = MoveViewModel(3, MoveFleet(MOVE), Moves(), backgroundScope, canWrite = true)
        runCurrent()
        assertEquals(listOf("oak"), vm.state.value.targets.map { it.alias })
    }

    @Test
    fun picking_a_host_previews_and_move_opens_the_moved_session() = runTest {
        val moves = Moves()
        val vm = MoveViewModel(3, MoveFleet(MOVE), moves, backgroundScope, canWrite = true)
        vm.open()
        vm.selectTarget("oak").join()
        runCurrent()
        assertEquals("feat/x", vm.state.value.preview?.branch)
        var opened: Long? = null

        vm.move { opened = it }.join()
        runCurrent()

        assertEquals(listOf("preview oak", "move oak keep=false idle=false"), moves.calls)
        assertEquals(9L, opened)
        assertFalse(vm.state.value.open)
    }

    @Test
    fun a_move_when_idle_waits_and_the_wait_can_be_cancelled() = runTest {
        val moves = Moves()
        moves.answer = MoveOutcome.Waiting("oak", deadlineUnix = 1_000)
        val vm = MoveViewModel(3, MoveFleet(MOVE), moves, backgroundScope, canWrite = true)
        vm.selectTarget("oak").join()
        vm.setWhenIdle(true)
        vm.move {}.join()
        runCurrent()
        assertEquals("oak", vm.state.value.waiting?.toHost)

        vm.cancelWait().join()
        runCurrent()
        assertNull(vm.state.value.waiting)
        assertEquals("cancel oak", moves.calls.last())
    }

    @Test
    fun a_readonly_pairing_is_not_offered_a_move() = runTest {
        val vm = MoveViewModel(3, MoveFleet(MOVE), Moves(), backgroundScope, canWrite = false)
        runCurrent()
        assertFalse(vm.state.value.available)
    }

    @Test
    fun a_reply_is_read_by_its_kind() {
        fun read(text: String) = moveOutcomeOf(json.parseToJsonElement(text)) { s, e -> json.decodeFromJsonElement(s, e) }
        assertIs<MoveOutcome.Preview>(read("""{"kind":"preview","branch":"b","target":{"state":"absent"}}"""))
        assertEquals(MoveOutcome.Waiting("oak", 5), read("""{"kind":"waiting","session_id":3,"to_host":"oak","deadline_unix":5}"""))
        assertEquals(MoveOutcome.WaitCancelled(true), read("""{"kind":"wait_cancelled","session_id":3,"was_waiting":true}"""))
        val moved = assertIs<MoveOutcome.Moved>(read("""{"kind":"moved","source_killed":true,"warnings":["w"],"target":{"id":9,"tmux_name":"s","host_alias":"oak"}}"""))
        assertEquals(9L, moved.target.id)
    }

    // --- the New bar (redesign 14.5) ---

    @Test
    fun an_offline_host_is_listed_with_why_and_cannot_be_picked() = runTest {
        val moves = Moves()
        val vm = MoveViewModel(3, MoveFleet(MOVE), moves, backgroundScope, canWrite = true)
        runCurrent()
        assertEquals(listOf("down"), vm.state.value.offline.map { it.alias }, "shown, unreachable, not the source")

        vm.selectTarget("down").join()
        runCurrent()

        assertNull(vm.state.value.target)
        assertEquals(emptyList(), moves.calls, "no dry run against a host the hub cannot reach")
    }

    @Test
    fun each_option_counts_what_runs_there() = runTest {
        val vm = MoveViewModel(3, MoveFleet(MOVE), Moves(), backgroundScope, canWrite = true)
        runCurrent()
        assertEquals(1, vm.state.value.running["pine"])
        assertNull(vm.state.value.running["oak"])
    }

    @Test
    fun the_new_bar_opens_with_no_host_chosen() = runTest {
        val vm = MoveViewModel(3, MoveFleet(MOVE), Moves(), backgroundScope, canWrite = true)
        vm.open(fresh = true)
        runCurrent()
        assertNull(vm.state.value.target, "nothing pre-selected")
        vm.selectTarget("oak").join()
        vm.close()
        vm.open(fresh = true)
        runCurrent()
        assertNull(vm.state.value.target, "an earlier pick is not carried into the next Move")
        assertNull(vm.state.value.preview)
    }
}
