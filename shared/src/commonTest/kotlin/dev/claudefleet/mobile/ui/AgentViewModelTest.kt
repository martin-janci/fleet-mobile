package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AgentActions
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fleet's agent on the phone: offered only when the hub lists
 * `ensure_operator` and the pairing can write; one press wakes it and opens
 * its session on the ordinary Session screen.
 */
class AgentViewModelTest {
    private val withAgent = HubCapabilities.of(ToolCatalog(setOf("work", "ensure_operator")))

    private class FakeAgent : AgentActions {
        var calls = 0
        var answer: CompletableDeferred<SessionRow> = CompletableDeferred(SessionRow(id = 42))
        override suspend fun ensureOperator(): SessionRow {
            calls++
            return answer.await()
        }
    }

    @Test
    fun it_is_not_offered_by_a_hub_without_the_agent() = runTest {
        val actions = FakeAgent()
        val vm = AgentViewModel(WorkFleet(caps = HubCapabilities.of(ToolCatalog(setOf("work")))), actions, backgroundScope, canWrite = true) {}
        runCurrent()
        assertFalse(vm.state.value.available)
        assertNull(vm.open())
        assertEquals(0, actions.calls)
    }

    @Test
    fun a_readonly_pairing_is_not_offered_it() = runTest {
        val actions = FakeAgent()
        val vm = AgentViewModel(WorkFleet(caps = withAgent), actions, backgroundScope, canWrite = false) {}
        runCurrent()
        assertFalse(vm.state.value.available)
        assertNull(vm.open())
        assertEquals(0, actions.calls)
    }

    @Test
    fun it_wakes_the_agent_once_and_opens_its_session() = runTest {
        val actions = FakeAgent().apply { answer = CompletableDeferred() }
        val opened = mutableListOf<Long>()
        val vm = AgentViewModel(WorkFleet(caps = withAgent), actions, backgroundScope, canWrite = true) { opened += it }
        runCurrent()
        assertTrue(vm.state.value.available)

        val job = assertNotNull(vm.open())
        runCurrent()
        assertTrue(vm.state.value.waking)
        // A second press while the first is in flight does not start another.
        assertNull(vm.open())

        actions.answer.complete(SessionRow(id = 42))
        job.join()
        runCurrent()
        assertEquals(1, actions.calls)
        assertEquals(listOf(42L), opened)
        assertFalse(vm.state.value.waking)
    }

    @Test
    fun a_refusal_is_said_and_can_be_dismissed() = runTest {
        val actions = FakeAgent().apply {
            answer = CompletableDeferred<SessionRow>().also { it.completeExceptionally(HubError.Tool("E_MCP_OFF", "the control API is off")) }
        }
        val opened = mutableListOf<Long>()
        val vm = AgentViewModel(WorkFleet(caps = withAgent), actions, backgroundScope, canWrite = true) { opened += it }
        vm.open()!!.join()
        runCurrent()
        assertEquals(emptyList(), opened)
        assertEquals("the control API is off", vm.state.value.error?.body)
        assertFalse(vm.state.value.waking)
        vm.dismissError()
        runCurrent()
        assertNull(vm.state.value.error)
    }
}
