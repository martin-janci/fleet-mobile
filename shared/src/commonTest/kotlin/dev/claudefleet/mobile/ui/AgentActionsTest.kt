package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.BuiltInAgentActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AgentAction
import dev.claudefleet.mobile.model.AgentJob
import dev.claudefleet.mobile.model.AgentRule
import dev.claudefleet.mobile.model.AgentRunForm
import dev.claudefleet.mobile.model.AgentRunStarted
import dev.claudefleet.mobile.model.AgentsStatus
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SELF_DEBUG
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.agentRunArgs
import dev.claudefleet.mobile.model.agentActionTitle
import dev.claudefleet.mobile.model.ruleAgentName
import dev.claudefleet.mobile.model.ruleLine
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
    override suspend fun revoke(id: Long) {
        calls += "revoke $id"
        refuse?.let { throw it }
    }

    override suspend fun run(id: Long, form: AgentRunForm): AgentRunStarted {
        calls += "run $id ${form.request}"
        refuse?.let { throw it }
        return AgentRunStarted(id, "r$id:manual:1:abcd")
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
        assertEquals("Teach the support KB: errors/a.yaml", agentActionTitle(AgentAction(id = 1, kind = "kb_learn", payload = """{"path":"errors/a.yaml"}""")))
        assertEquals(
            "File a bug in PD for a logged error: x",
            agentActionTitle(AgentAction(id = 1, kind = "jira_create_bug", payload = """{"project":"PD","source_key":null,"summary":"x"}""")),
        )
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
    fun a_fleet_agents_action_and_rule_say_what_they_are() {
        fun title(kind: String, payload: String) = agentActionTitle(AgentAction(id = 1, kind = kind, payload = payload))
        assertEquals("Comment on L2S-7", title("tracker_comment", """{"key":"L2S-7"}"""))
        assertEquals(
            "File a bug in BUG for L2S-7: Key expired",
            title("tracker_issue", """{"project":"BUG","issue_type":"Bug","summary":"Key expired","link":{"to":"L2S-7"}}"""),
        )
        assertEquals("Link BUG-9 to L2S-7 (causes)", title("tracker_link", """{"from":"BUG-9","to":"L2S-7","link_type":"Causes"}"""))
        assertEquals("Remember NPE in memory", title("record", """{"store":"memory","key":"NPE"}"""))
        assertEquals("Call kb.add_known_error", title("mcp_call", """{"binding":"kb","tool":"add_known_error"}"""))
        assertEquals("Comment on o/r#4", title("pr_comment", """{"repo":"o/r","number":4}"""))
        val s = json.decodeFromString(
            AgentsStatus.serializer(),
            """{"rules":[{"id":12,"agent":"fleet","scope":"l2-prod","level":"propose","host_alias":"mercury",
               "project_id":null,"config":"{}","granted_by":"x","granted_at":1,"expires_at":null,
               "definition":"l2-support","bindings":"[]","active":true},
              {"id":3,"agent":"review","scope":"gh:o/r","level":"watch","host_alias":"box","active":false}],
              "jobs":[],"pending":[]}""",
        )
        assertEquals(listOf("l2-support", "PR reviewer"), s.rules.map { ruleAgentName(it) })
        assertEquals(listOf("l2-prod at propose on mercury", "gh:o/r at watch on box (expired)"), s.rules.map { ruleLine(it) })
    }

    @Test
    fun a_rule_is_revoked_and_leaves_the_list() = runTest {
        val rule = AgentRule(id = 12, agent = "fleet", scope = "l2-prod", level = "watch", hostAlias = "m", definition = "l2-support")
        val agents = object : BuiltInAgentActions by FakeAgents() {
            val calls = mutableListOf<String>()
            override suspend fun status() = AgentsStatus(rules = listOf(rule))
            override suspend fun revoke(id: Long) {
                calls += "revoke $id"
            }
        }
        val vm = AgentActionsViewModel(AgentsFleet(withAgents), agents, backgroundScope, canWrite = true)
        runCurrent()
        vm.refresh()?.join()
        runCurrent()
        assertEquals(listOf(rule), vm.state.value.rules)
        vm.revoke(rule)?.join()
        runCurrent()
        assertEquals(listOf("revoke 12"), agents.calls)
        assertEquals(emptyList(), vm.state.value.rules)
        assertEquals("l2-prod revoked.", vm.state.value.ruleNotice)
    }

    @Test
    fun run_starts_a_manual_fleet_agent_and_the_card_follows_its_job() = runTest {
        val selfDebug = AgentRule(id = 4, agent = "fleet", scope = "me", level = "watch", hostAlias = "m", definition = SELF_DEBUG)
        val review = AgentRule(id = 3, agent = "review", scope = "gh:o/r", level = "watch", hostAlias = "m")
        var jobs = emptyList<AgentJob>()
        val agents = object : BuiltInAgentActions by FakeAgents() {
            val calls = mutableListOf<String>()
            override suspend fun status() = AgentsStatus(rules = listOf(selfDebug, review), jobs = jobs)
            override suspend fun run(id: Long, form: AgentRunForm): AgentRunStarted {
                calls += "run $id ${agentRunArgs(id, form)}"
                return AgentRunStarted(id, "r4:manual:9:abcd")
            }
        }
        val vm = AgentActionsViewModel(AgentsFleet(withAgents), agents, backgroundScope, canWrite = true)
        runCurrent()
        vm.refresh()?.join()
        runCurrent()
        assertTrue(vm.state.value.canRun)
        assertNull(vm.state.value.selfDebug)
        // A built-in agent has no Run….
        assertNull(vm.run(review, AgentRunForm()))

        jobs = listOf(AgentJob(id = 1, ruleId = 4, dedupKey = "r4:manual:9:abcd", state = "pending"))
        vm.run(selfDebug, AgentRunForm(request = "why", minutes = 60))?.join()
        runCurrent()
        assertEquals(listOf("""run 4 {"action":"run","id":4,"request":"why","run_scope":{"minutes":60}}"""), agents.calls)
        assertEquals("pending", vm.state.value.selfDebug?.job?.state)
        assertNull(vm.state.value.selfDebug?.report)
        assertEquals("self-debug queued. Its findings show here when it is done.", vm.state.value.ruleNotice)

        jobs = listOf(
            AgentJob(
                id = 1, ruleId = 4, dedupKey = "r4:manual:9:abcd", state = "done",
                result = """{"answer":{"summary":"ok","healthy":true,"findings":[{"title":"a","severity":"info"},{"title":"b","severity":"high"}]}}""",
            ),
        )
        vm.refresh()?.join()
        runCurrent()
        assertEquals(listOf("b", "a"), vm.state.value.selfDebug?.report?.findings?.map { it.title })
    }

    @Test
    fun a_readonly_pairing_or_an_older_hub_has_no_run() = runTest {
        val readonly = AgentActionsViewModel(AgentsFleet(withAgents), FakeAgents(), backgroundScope, canWrite = false)
        val older = AgentActionsViewModel(
            AgentsFleet(HubCapabilities(tools = setOf(AgentActionsViewModel.TOOL), actions = mapOf(AgentActionsViewModel.TOOL to setOf("status", "approve", "reject", "revoke")))),
            FakeAgents(),
            backgroundScope,
            canWrite = true,
        )
        runCurrent()
        assertFalse(readonly.state.value.canRun)
        assertFalse(older.state.value.canRun)
        assertTrue(older.state.value.canAnswer)
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
