package dev.claudefleet.mobile.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A work item's result (claude-fleet `work { result, item_id }`, design
 * 2026-10-13 D6): the newest finished task that left a report, with what
 * fleet read itself, the completion gate and the PRs. The desktop task
 * page's *Result*; the phone shows the report, the gate and the PRs.
 */
data class TaskResult(
    val taskId: Long,
    val at: Long,
    val role: String,
    val marker: String?,
    val report: TaskReport,
    /** The report as the hub served it, for the card's Source toggle. */
    val raw: String,
    /** `passed | unverified | blocked`. */
    val gate: String,
    /** The `done_when` lines that failed, as fleet read them. */
    val failedChecks: List<String>,
    /** Subtasks still open, by title. */
    val openChildren: List<String>,
    val prs: List<ResultPr>,
)

data class ResultPr(val url: String, val number: Long?, val state: String?)

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The hub's answer read leniently; `null` (no result yet) or a shape it does not know is no card. */
fun taskResultFrom(v: JsonElement): TaskResult? {
    if (v !is JsonObject) return null
    val reportJson = v["report"] ?: return null
    val report = reportFromValue(reportJson) ?: return null
    val gate = v["gate"] as? JsonObject
    val checks = (gate?.get("checks") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    return TaskResult(
        taskId = (v["task_id"] as? JsonPrimitive)?.longOrNull ?: return null,
        at = (v["at"] as? JsonPrimitive)?.longOrNull ?: 0,
        role = v["role"].str().orEmpty(),
        marker = v["marker"].str()?.takeIf { it.isNotBlank() },
        report = report,
        raw = reportJson.toString(),
        gate = gate?.get("state").str() ?: "unverified",
        failedChecks = checks.filter { it["state"].str() == "fail" }.mapNotNull { c ->
            val line = c["line"].str()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val detail = c["detail"].str()?.takeIf { it.isNotBlank() }
            if (detail != null) "$line: $detail" else line
        },
        openChildren = (gate?.get("open_children") as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("title").str() },
        prs = (v["prs"] as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val url = o["url"].str()?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            ResultPr(url, (o["number"] as? JsonPrimitive)?.longOrNull, o["state"].str())
        },
    )
}

/** "Checks passed" / "Not verified yet" / "Blocked: 2 subtasks open": the gate in one line. */
fun resultGateLine(r: TaskResult): String = when (r.gate) {
    "passed" -> "Checks passed"
    "blocked" -> buildList {
        if (r.failedChecks.isNotEmpty()) add(if (r.failedChecks.size == 1) "1 check failed" else "${r.failedChecks.size} checks failed")
        if (r.openChildren.isNotEmpty()) add(if (r.openChildren.size == 1) "1 subtask open" else "${r.openChildren.size} subtasks open")
    }.ifEmpty { listOf("no evidence of the work") }.joinToString(", ", prefix = "Blocked: ")
    else -> "Not verified yet"
}

/** "PR #12 · merged", or the URL's tail when the hub sent no number. */
fun resultPrLabel(p: ResultPr): String =
    listOfNotNull(p.number?.let { "PR #$it" } ?: p.url.substringAfterLast('/').let { "PR $it" }, p.state?.lowercase()).joinToString(" · ")
