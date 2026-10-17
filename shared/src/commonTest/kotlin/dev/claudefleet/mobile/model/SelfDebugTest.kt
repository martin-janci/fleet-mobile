package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val RESULT = """{"subject":{"request":"why did the phone drop?"},
  "answer":{"summary":"The hub restarted twice.","healthy":false,"findings":[
    {"title":"Stale agent","severity":"low","component":"agent","cause":"old build","fix":"update it","confidence":0.6,
     "fingerprint":"a","evidence":[]},
    {"title":"Hub OOM","severity":"critical","component":"hub","cause":"memory","fix":"raise the limit","confidence":"high",
     "fingerprint":"b","evidence":[{"source":"hub","at":"2026-10-17T10:15:40Z","quote":"killed"}]},
    {"title":"Slow probe","severity":"medium"},
    {"severity":"HIGH","component":"phone","cause":"reconnect storm"},
    {"title":"Odd","severity":"whatever"},
    {"title":"Note","severity":"info"}
  ]},
  "actions":[]}"""

class SelfDebugTest {
    @Test
    fun a_result_reads_as_its_summary_and_findings_worst_first() {
        val r = selfDebugReport(RESULT)!!
        assertEquals("The hub restarted twice.", r.summary)
        assertEquals(false, r.healthy)
        assertEquals(
            listOf("Hub OOM", "Untitled finding", "Slow probe", "Stale agent", "Note", "Odd"),
            r.findings.map { it.title },
        )
        assertEquals(listOf("critical", "high", "medium", "low", "info", "whatever"), r.findings.map { it.severity })
        val oom = r.findings.first()
        assertEquals("hub", oom.component)
        assertEquals("raise the limit", oom.fix)
        assertEquals("high", oom.confidence)
        assertEquals(listOf(FindingEvidence("hub", "2026-10-17T10:15:40Z", "killed")), oom.evidence)
        assertEquals("0.6", r.findings.first { it.title == "Stale agent" }.confidence)
        // Missing fields read as empty, not as a failure.
        val slow = r.findings.first { it.title == "Slow probe" }
        assertEquals("" to "", slow.component to slow.cause)
        assertTrue(slow.evidence.isEmpty())
    }

    @Test
    fun a_result_without_an_answer_is_no_report() {
        assertNull(selfDebugReport(null))
        assertNull(selfDebugReport("not json"))
        assertNull(selfDebugReport("""{"subject":{}}"""))
        val empty = selfDebugReport("""{"answer":{}}""")!!
        assertEquals("", empty.summary)
        assertNull(empty.healthy)
        assertTrue(empty.findings.isEmpty())
    }

    @Test
    fun the_run_form_leaves_out_what_is_empty() {
        assertEquals("""{"action":"run","id":4}""", agentRunArgs(4, AgentRunForm(request = "  ", host = " ")).toString())
        val full = agentRunArgs(
            4,
            AgentRunForm(request = " why the drop? ", minutes = 1440, component = "phone", host = " pine ", session = "42"),
        )
        assertEquals("why the drop?", full["request"]!!.jsonPrimitive.content)
        val scope = full["run_scope"]!!.jsonObject
        assertEquals(mapOf("minutes" to "1440", "host" to "pine", "session" to "42", "component" to "phone"), scope.mapValues { it.value.jsonPrimitive.content })
        // A component the hub does not know is dropped; a long request is cut at the hub's limit.
        val odd = agentRunArgs(4, AgentRunForm(request = "x".repeat(5000), component = "toaster"))
        assertFalse("run_scope" in odd)
        assertEquals(RUN_REQUEST_MAX, odd["request"]!!.jsonPrimitive.content.length)
        assertEquals(listOf(60, 360, 1440, 4320, 10080), RUN_WINDOWS.map { it.second })
        assertTrue(json.decodeFromString(JsonObject.serializer(), full.toString()) == full)
    }

    @Test
    fun the_card_follows_the_run_started_here_else_the_latest_finished_one() {
        val rules = listOf(
            AgentRule(id = 1, agent = "fleet", definition = SELF_DEBUG),
            AgentRule(id = 2, agent = "fleet", definition = "l2-support"),
        )
        val jobs = listOf(
            AgentJob(id = 10, ruleId = 1, dedupKey = "r1:manual:1:a", state = "done", result = RESULT, updatedAt = 100),
            AgentJob(id = 11, ruleId = 1, dedupKey = "r1:manual:2:b", state = "done", result = RESULT, updatedAt = 200),
            AgentJob(id = 12, ruleId = 1, dedupKey = "r1:manual:3:c", state = "running", updatedAt = 300),
            AgentJob(id = 13, ruleId = 2, dedupKey = "r2:manual:4:d", state = "done", result = "{}", updatedAt = 400),
        )
        assertEquals(11L, selfDebugJob(rules, jobs, null)?.id)
        assertEquals(12L, selfDebugJob(rules, jobs, "r1:manual:3:c")?.id)
        // Another agent's run is not a self debug one.
        assertEquals(11L, selfDebugJob(rules, jobs, "r2:manual:4:d")?.id)
        assertNull(selfDebugJob(rules.drop(1), jobs, null))
        // The hub's job row decodes, its extra fields ignored.
        val s = json.decodeFromString(
            AgentsStatus.serializer(),
            """{"rules":[],"pending":[],"jobs":[{"id":5,"rule_id":1,"agent":"fleet","subject":"manual:1","trigger":"manual",
               "dedup_key":"r1:manual:1:a","state":"done","attempts":1,"next_attempt_at":0,"lease_until":null,
               "error_kind":null,"error":null,"result":"{}","cost_micros":0,"created_at":1,"updated_at":2}]}""",
        )
        assertEquals(AgentJob(id = 5, ruleId = 1, dedupKey = "r1:manual:1:a", state = "done", result = "{}", createdAt = 1, updatedAt = 2), s.jobs.single())
    }
}
