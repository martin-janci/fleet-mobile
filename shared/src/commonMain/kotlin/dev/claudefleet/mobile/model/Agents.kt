package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * The built-in agents' actions waiting for a person (claude-fleet agents
 * design step 5, the hub's `agents` tool): a review the PR reviewer wants to
 * post, a write the support agent wants to make. The phone lists them in the
 * Inbox with Approve and Reject; the desktop's `src/lib/agents.ts` is the
 * other reader. Only what the phone draws is decoded.
 */

/** One action an agent proposed (`agent_actions`). */
@Serializable
data class AgentAction(
    val id: Long,
    @SerialName("job_id") val jobId: Long = 0,
    /** `post_review`, … — tolerant of more. */
    val kind: String = "",
    /** Exactly what will be sent, JSON text. */
    val payload: String = "{}",
    /** `pending` | `approved` | `rejected` | `executed` | `failed` | `expired`. */
    val state: String = "pending",
    @SerialName("created_at") val createdAt: Long = 0,
)

/**
 * One standing rule (`agent_rules`): a built-in agent on a repo or a Jira
 * project, or an instance of a catalog fleet agent (claude-fleet's
 * configurable agents, step 5), named by its `definition`.
 */
@Serializable
data class AgentRule(
    val id: Long,
    /** `review` | `support` | `fleet`. */
    val agent: String = "",
    /** `gh:<owner>/<repo>`, `jira:<id>:<PROJECT>`, or a fleet agent's instance name. */
    val scope: String = "",
    /** `watch` | `propose` | `act`. */
    val level: String = "",
    @SerialName("host_alias") val hostAlias: String = "",
    /** A fleet agent's catalog definition. */
    val definition: String? = null,
    /** Not expired. */
    val active: Boolean = true,
)

/** One run of an agent (`agent_jobs`): what the findings card reads. */
@Serializable
data class AgentJob(
    val id: Long,
    @SerialName("rule_id") val ruleId: Long? = null,
    /** A manual run's is the `job` that `agents { action: run }` answered. */
    @SerialName("dedup_key") val dedupKey: String = "",
    /** `pending` | `running` | `done` | `failed` | `skipped` | `needs_person`. */
    val state: String = "",
    val error: String? = null,
    /** The run's answer, JSON text; null until it is done. */
    val result: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** `agents { action: status }`: the part the phone draws. */
@Serializable
data class AgentsStatus(
    val pending: List<AgentAction> = emptyList(),
    val rules: List<AgentRule> = emptyList(),
    val jobs: List<AgentJob> = emptyList(),
)

/** `agents { action: run }`'s answer: the rule, and the queued job's dedup key. */
@Serializable
data class AgentRunStarted(val id: Long = 0, val job: String = "")

/** A rule's agent in words, the desktop's `ruleAgentName`. */
fun ruleAgentName(r: AgentRule): String = when (r.agent) {
    "review" -> "PR reviewer"
    "support" -> "Support"
    else -> r.definition ?: r.agent
}

/** "l2-prod at propose on mercury", the desktop's `ruleLine`. */
fun ruleLine(r: AgentRule): String = "${r.scope} at ${r.level} on ${r.hostAlias}${if (r.active) "" else " (expired)"}"

private val lenient = Json { ignoreUnknownKeys = true }

private fun payloadOf(a: AgentAction): JsonObject? =
    runCatching { lenient.parseToJsonElement(a.payload) as? JsonObject }.getOrNull()

/**
 * What an action will do, the desktop's `actionTitle`: "Post a review on
 * o/r#4: request changes, 2 comments"; another kind by its words.
 */
fun agentActionTitle(a: AgentAction): String {
    val p = payloadOf(a)
    if (a.kind == "post_review" && p != null) {
        val repo = (p["repo"] as? JsonPrimitive)?.contentOrNull ?: "?"
        val number = (p["number"] as? JsonPrimitive)?.contentOrNull ?: "?"
        val event = ((p["event"] as? JsonPrimitive)?.contentOrNull ?: "COMMENT").lowercase().replaceFirst('_', ' ')
        val n = (p["comments"] as? JsonArray)?.size ?: 0
        return "Post a review on $repo#$number: $event, $n comment${if (n == 1) "" else "s"}"
    }
    if (p != null) {
        fun f(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull ?: "?"
        when (a.kind) {
            "pr_labels" -> {
                fun names(k: String) = (p[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                val changes = names("add").map { "+$it" } + names("remove").map { "−$it" }
                return "Label ${f("repo")}#${f("number")}: ${changes.joinToString(" ")}"
            }
            "pr_description" -> return "Describe ${f("repo")}#${f("number")}"
            "debate_reply" -> return "Answer the test debate on ${f("repo")}#${f("number")}: ${f("decision")}"
            "jira_comment" -> return "Comment on ${f("key")}"
            "jira_create_bug" -> {
                val source = (p["source_key"] as? JsonPrimitive)?.contentOrNull
                return "File a bug in ${f("project")} for ${source ?: "a logged error"}: ${f("summary")}"
            }
            "kb_learn" -> return "Teach the support KB: ${f("path")}"
            "jira_link" -> return "Link ${f("bug_key")} to ${f("source_key")} (${f("link_type").lowercase()})"
            // A catalog fleet agent's (configurable agents, step 4).
            "tracker_comment" -> return "Comment on ${f("key")}"
            "tracker_issue" -> {
                val to = ((p["link"] as? JsonObject)?.get("to") as? JsonPrimitive)?.contentOrNull
                val type = ((p["issue_type"] as? JsonPrimitive)?.contentOrNull ?: "Task").lowercase()
                return "File a $type in ${f("project")}${if (to != null) " for $to" else ""}: ${f("summary")}"
            }
            "tracker_link" -> return "Link ${f("from")} to ${f("to")} (${f("link_type").lowercase()})"
            "record" -> return "Remember ${f("key")} in ${f("store")}"
            "mcp_call" -> return "Call ${f("binding")}.${f("tool")}"
            "pr_comment" -> return "Comment on ${f("repo")}#${f("number")}"
            // Fleet's own: what an analysis proposes (self debug, proposer, simplify, refactor).
            "work_task" -> {
                val n = (p["steps"] as? JsonArray)?.size ?: 0
                return "Create a task: ${f("title")}${if (n > 0) " ($n step${if (n == 1) "" else "s"})" else ""}"
            }
            "fix_session" -> {
                val host = (p["host"] as? JsonPrimitive)?.contentOrNull
                return "Start a session on ${f("project")}${if (host != null) " ($host)" else ""}: ${f("title")}"
            }
            "setting" -> {
                val was = (p["was"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                return "Change ${f("key")}${if (was != null) " from $was" else ""} to ${f("value")}"
            }
            "session_recover" -> {
                val host = (p["host"] as? JsonPrimitive)?.contentOrNull
                return "Recover ${f("session")}${if (host != null) " on $host" else ""}"
            }
        }
    }
    return a.kind.replace('_', ' ')
}

// ---- Run…, and the self debug agent's findings (claude-fleet self debug agent, step 7) ----

/** The catalog definition of the self debug agent. */
const val SELF_DEBUG = "self-debug"

/** A fleet agent's instance the phone may start by hand; the hub refuses one whose trigger is not manual or schedule. */
fun ruleRunnable(r: AgentRule): Boolean = r.agent == "fleet" && r.definition != null && r.active

/** The Run… form's windows: label and minutes. */
val RUN_WINDOWS: List<Pair<String, Int>> = listOf("1 h" to 60, "6 h" to 360, "24 h" to 1440, "3 d" to 4320, "7 d" to 10080)

/** The components a run may be pointed at (`run_scope.component`). */
val RUN_COMPONENTS: List<String> =
    listOf("hub", "updater", "agent", "host", "desktop", "phone", "session", "routine", "mission", "jev", "update")

/** Most characters of a run's request (the hub refuses more). */
const val RUN_REQUEST_MAX = 4000

/** What the Run… form holds; blank fields are left out of the call. */
data class AgentRunForm(
    val request: String = "",
    /** One of [RUN_WINDOWS]' minutes, or null for the agent's own. */
    val minutes: Int? = null,
    /** One of [RUN_COMPONENTS], or null. */
    val component: String? = null,
    val host: String = "",
    val session: String = "",
)

/** `agents { action: run, id, request?, run_scope? }` for [form], every empty field left out. */
fun agentRunArgs(ruleId: Long, form: AgentRunForm): JsonObject {
    val request = form.request.trim().take(RUN_REQUEST_MAX)
    val host = form.host.trim()
    val session = form.session.trim()
    val component = form.component?.takeIf { it in RUN_COMPONENTS }
    val minutes = form.minutes?.coerceIn(1, 10080)
    val scope = buildJsonObject {
        minutes?.let { put("minutes", it) }
        if (host.isNotEmpty()) put("host", host)
        if (session.isNotEmpty()) put("session", session)
        component?.let { put("component", it) }
    }
    return buildJsonObject {
        put("action", "run")
        put("id", ruleId)
        if (request.isNotEmpty()) put("request", request)
        if (scope.isNotEmpty()) put("run_scope", scope)
    }
}

/** One quoted piece of evidence behind a finding. */
data class FindingEvidence(val source: String, val at: String, val quote: String)

/** One finding of a self debug run. */
data class SelfDebugFinding(
    val title: String,
    /** The self debug agent's `critical` | `major` | `minor`; other agents'
     *  `high` | `medium` | `low` | `info`; or whatever the agent wrote. */
    val severity: String,
    val component: String,
    val cause: String,
    val fix: String,
    val confidence: String,
    val evidence: List<FindingEvidence>,
)

/** A self debug run's answer: its summary and its findings, worst first. */
data class SelfDebugReport(val summary: String, val healthy: Boolean?, val findings: List<SelfDebugFinding>)

/** Where [severity] sorts: critical first, an unknown word last. */
fun severityRank(severity: String): Int = when (severity.lowercase()) {
    "critical" -> 0
    "high" -> 1
    "medium", "major" -> 2
    "low", "minor" -> 3
    "info" -> 4
    else -> 5
}

/**
 * A finished self debug job's `result` (`{"subject", "answer": {"summary",
 * "healthy", "findings": [...]}, "actions"}`) as a report, its findings worst
 * severity first and otherwise in the agent's order; null when it is not
 * JSON or has no answer. A field the agent left out reads as empty.
 */
fun selfDebugReport(result: String?): SelfDebugReport? {
    val root = result?.let { runCatching { lenient.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
    val answer = root["answer"] as? JsonObject ?: return null
    fun JsonObject.text(key: String): String = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull.orEmpty()
        null -> ""
        else -> v.toString()
    }
    val findings = (answer["findings"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { f ->
        SelfDebugFinding(
            title = f.text("title").ifBlank { "Untitled finding" },
            severity = f.text("severity").lowercase(),
            component = f.text("component"),
            cause = f.text("cause"),
            fix = f.text("fix"),
            confidence = f.text("confidence"),
            evidence = (f["evidence"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { e ->
                FindingEvidence(e.text("source"), e.text("at"), e.text("quote"))
            },
        )
    }.sortedBy { severityRank(it.severity) }
    return SelfDebugReport(
        summary = answer.text("summary"),
        healthy = (answer["healthy"] as? JsonPrimitive)?.booleanOrNull,
        findings = findings,
    )
}

/**
 * The self debug job the findings card shows: the one [wanted] names (the
 * run just started here, whatever its state), else the latest finished one
 * of any self debug instance; null when there is none.
 */
fun selfDebugJob(rules: List<AgentRule>, jobs: List<AgentJob>, wanted: String?): AgentJob? {
    val ids = rules.filter { it.definition == SELF_DEBUG }.map { it.id }.toSet()
    val ours = jobs.filter { it.ruleId != null && it.ruleId in ids }
    wanted?.let { key -> ours.firstOrNull { it.dedupKey == key }?.let { return it } }
    return ours.filter { it.state == "done" && it.result != null }.maxWithOrNull(compareBy<AgentJob>({ it.updatedAt }, { it.id }))
}
