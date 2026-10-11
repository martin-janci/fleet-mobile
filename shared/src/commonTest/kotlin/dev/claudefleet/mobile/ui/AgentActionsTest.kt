package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.BuiltInAgentActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AgentAction
import dev.claudefleet.mobile.model.AgentsStatus
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.agentActionTitle
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AgentsFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private const val REVIEW = """{"repo":"o/r","number":4,"event":"REQUEST_CHANGES","body":"x","comments":[{},{}]}"""

private fun action(id: Long = 7, payload: String = REVIEW) = AgentAction(id = id, jobId = 1, kind = "post_review", payload = payload)

private class FakeAgents(
    var pending: List<AgentAction> = listOf(action()),
    var refuse: Throwable? = null,
) : BuiltInAgentActions {
    val calls = mutableListOf<String>()

    override suspend fun status(): AgentsStatus {
        calls += "status"
        refuse?.let { throw it }
        return AgentsStatus(pending)
    }

    override suspend fun decide(id: Long, approve: Boolean): AgentAction {
        calls += "decide $id $approve"
        refuse?.let { throw it }
        return action(id).copy(state = if (approve) "approved" else "rejected")
    }
}

private val withAgents = HubCapabilities(tools = setOf(AgentActionsViewModel.TOOL))

class AgentActionsTest {
    @Test
    fun an_action_says_what_it_will_post() {
        assertEquals("Post a review on o/r#4: request changes, 2 comments", agentActionTitle(action()))
        assertEquals(
            "Post a review on o/r#4: approve, 1 comment",
            agentActionTitle(action(payload = """{"repo":"o/r","number":4,"event":"APPROVE","comments":[{}]}""")),
        )
        assertEquals("jira comment", agentActionTitle(AgentAction(id = 1, kind = "jira_comment", payload = "not json")))
        assertEquals(
            "Label o/r#4: +core −lgtm",
            agentActionTitle(AgentAction(id = 1, kind = "pr_labels", payload = """{"repo":"o/r","number":4,"add":["core"],"remove":["lgtm"]}""")),
        )
        assertEquals(
            "Answer the test debate on o/r#4: hold",
            agentActionTitle(AgentAction(id = 1, kind = "debate_reply", payload = """{"repo":"o/r","number":4,"decision":"hold"}""")),
        )
        assertEquals("Describe o/r#4", agentActionTitle(AgentAction(id = 1, kind = "pr_description", payload = """{"repo":"o/r","number":4}""")))
        assertEquals("Comment on L2S-7", agentActionTitle(AgentAction(id = 1, kind = "jira_comment", payload = """{"key":"L2S-7"}""")))
        assertEquals(
            "File a bug in PD for L2S-7: [pay] NPE",
            agentActionTitle(
                AgentAction(id = 1, kind = "jira_create_bug", payload = """{"project":"PD","source_key":"L2S-7","summary":"[pay] NPE"}"""),
            ),
        )
        assertEquals(
            "Link PD-4 to L2S-7 (causes)",
            agentActionTitle(
                AgentAction(id = 1, kind = "jira_link", payload = """{"bug_key":"PD-4","source_key":"L2S-7","link_type":"Causes"}"""),
            ),
        )
        // The hub's row decodes, its extra fields ignored.
        val row = json.decodeFromString(
            AgentsStatus.serializer(),
            """{"rules":[],"jobs":[],"pending":[{"id":3,"job_id":9,"kind":"post_review","payload":"{}","state":"pending",
               "decided_by":null,"decided_at":null,"executed_at":null,"error":null,"created_at":100}]}""",
        )
        assertEquals(listOf(3L to 100L), row.pending.map { it.id to it.createdAt })
    }

    @Test
    fun a_hub_without_the_tool_shows_nothing_and_asks_nothing() = runTest {
        val agents = FakeAgents()
        val vm = AgentActionsViewModel(AgentsFleet(HubCapabilities(tools = setOf("work"))), agents, backgroundScope, canWrite = true)
        runCurrent()
        assertFalse(vm.state.value.available)
        assertNull(vm.refresh())
        assertTrue(agents.calls.isEmpty())
    }

    @Test
    fun approve_answers_and_takes_the_row_out() = runTest {
        val agents = FakeAgents()
        val vm = AgentActionsViewModel(AgentsFleet(withAgents), agents, backgroundScope, canWrite = true)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(listOf(7L), vm.state.value.pending.map { it.id })
        assertTrue(vm.state.value.canAnswer)
        vm.approve(action())
        runCurrent()
        assertEquals(listOf("status", "decide 7 true"), agents.calls)
        assertTrue(vm.state.value.pending.isEmpty())
        assertEquals("Approved: it goes out on the hub's next pass.", vm.state.value.notices[7])
    }

    @Test
    fun a_readonly_pairing_reads_but_never_answers() = runTest {
        val agents = FakeAgents()
        val vm = AgentActionsViewModel(AgentsFleet(withAgents), agents, backgroundScope, canWrite = false)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertEquals(1, vm.state.value.pending.size)
        assertFalse(vm.state.value.canAnswer)
        assertNull(vm.reject(action()))
        assertEquals(listOf("status"), agents.calls)
    }

    @Test
    fun a_device_the_hub_refuses_stops_asking() = runTest {
        val agents = FakeAgents(refuse = HubError.Tool("E_FORBIDDEN", "not the owner's device"))
        val vm = AgentActionsViewModel(AgentsFleet(withAgents), agents, backgroundScope, canWrite = true)
        runCurrent()
        vm.refresh()
        runCurrent()
        assertFalse(vm.state.value.available)
        assertNull(vm.refresh())
        assertEquals(listOf("status"), agents.calls)
    }
}
