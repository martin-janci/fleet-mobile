package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

/** `agents { action: status }`: the part the phone draws. */
@Serializable
data class AgentsStatus(
    val pending: List<AgentAction> = emptyList(),
    val rules: List<AgentRule> = emptyList(),
)

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
        }
    }
    return a.kind.replace('_', ' ')
}
