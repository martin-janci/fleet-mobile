package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs

/**
 * A mission (claude-fleet orchestration O1–O8): a goal a person runs as a
 * graph of tasks, which the hub's loop drives as far as a grant lets it.
 * `work { action: missions }` lists them; the fields the phone draws, and
 * nothing it would only carry.
 */
@Serializable
data class Mission(
    val id: Long,
    val name: String = "",
    val goal: String = "",
    /** `finite` | `continuous` — tolerant of more. */
    val mode: String = "finite",
    /** `draft` | `active` | `paused` | `completed` | `failed` | `cancelled`. */
    val state: String = "draft",
    /** The autonomy asked for, 0–3. */
    val level: Int = 0,
    val total: Int = 0,
    val done: Int = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val version: Long = 0,
)

/** A member of a mission (`WorkItemRow`, the fields a line needs). */
@Serializable
data class MissionItem(
    val id: Long,
    val key: String? = null,
    val title: String = "",
    @SerialName("status_category") val statusCategory: String = "",
)

/** One node of the mission's graph: its derived state and wave. */
@Serializable
data class MissionNode(
    @SerialName("item_id") val itemId: Long,
    /** done | proposed | rejected | running | failed | held | doing | blocked | waiting | ready. */
    val state: String = "",
    val wave: Int = 0,
)

@Serializable
data class MissionGraph(val nodes: List<MissionNode> = emptyList())

/** One next step of the loop (`orchestrate::steps::Step`). */
@Serializable
data class MissionStep(
    /** `run` | `retry` | `review` | `test` | `integrate` | `close` | `complete` | `ask`. */
    val kind: String,
    @SerialName("item_id") val itemId: Long? = null,
    val role: String? = null,
    val reason: String = "",
    /** The loop may take it under a grant; an ask is a person's only. */
    val auto: Boolean = false,
)

/** One card of the confirm queue (`store::CardRow`): what the planner or the loop proposes. */
@Serializable
data class MissionCard(
    val id: Long,
    @SerialName("mission_id") val missionId: Long = 0,
    /** `planner` | `loop`. */
    val source: String = "",
    val kind: String = "",
    @SerialName("work_item_id") val workItemId: Long? = null,
    val payload: JsonObject? = null,
    /** `open` | `applied` | `dismissed` | `refused` | `stale`. */
    val state: String = "open",
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

/** A person's signature on what the loop may do on its own. */
@Serializable
data class MissionGrant(
    val level: Int = 0,
    @SerialName("granted_by") val grantedBy: String = "",
    @SerialName("budget_micros") val budgetMicros: Long? = null,
    @SerialName("expires_at") val expiresAt: Long = 0,
)

/** The autonomy that applies: the least of the mission's level, the fleet's ceiling and a live grant. */
@Serializable
data class MissionAutonomy(
    val asked: Int = 0,
    val ceiling: Int = 0,
    val effective: Int = 0,
    val grant: MissionGrant? = null,
    val why: String = "",
    val enabled: Boolean = true,
)

/** The loop's view of a mission: its next steps, the cards waiting, the autonomy, what it spent. */
@Serializable
data class MissionPlan(
    val steps: List<MissionStep> = emptyList(),
    val cards: List<MissionCard> = emptyList(),
    val autonomy: MissionAutonomy = MissionAutonomy(),
    @SerialName("cost_micros") val costMicros: Long = 0,
)

/** `work { action: mission }`. */
@Serializable
data class MissionDetail(
    val mission: Mission,
    val items: List<MissionItem> = emptyList(),
    /** `running` | `blocked` | `waiting`, for an active mission only. */
    val phase: String? = null,
    val graph: MissionGraph = MissionGraph(),
    /** Whether this caller may change it: the buttons, not a fence. */
    @SerialName("may_change") val mayChange: Boolean = false,
    /** Absent for a draft or a finished mission, and from a hub before the loop. */
    val plan: MissionPlan? = null,
)

/** One step's outcome (`orchestrate::StepResult`). */
@Serializable
data class StepResult(
    val step: MissionStep,
    val ok: Boolean = false,
    val detail: String = "",
)

/** What `work_link { mission_start }` answers. */
@Serializable
data class StartOutcome(
    @SerialName("mission_id") val missionId: Long = 0,
    val results: List<StepResult> = emptyList(),
)

/** The key `mission_start` takes for one step (`run:12`), as the desktop's `stepKey`. */
fun MissionStep.key(): String = "$kind:${itemId ?: 0}"

/** A step as a short line, as the desktop's `stepLine`. */
fun MissionStep.line(): String {
    val verb = when (kind) {
        "run" -> "Run"
        "retry" -> "Retry"
        "review" -> "Review"
        "test" -> "Test"
        "integrate" -> "Integrate"
        "close" -> "Close"
        "complete" -> "Complete the mission"
        "ask" -> "Ask"
        else -> kind
    }
    return "$verb: $reason"
}

private fun JsonObject.text(key: String): String? = when (val v = this[key]) {
    null -> null
    is JsonPrimitive -> v.contentOrNull
    else -> v.toString()
}

/** What a card asks, in a sentence, as the desktop's `cardLine`. */
fun MissionCard.line(): String {
    val p = payload ?: JsonObject(emptyMap())
    val item = workItemId?.let { " task $it" } ?: ""
    return when (kind) {
        "create" -> {
            val tree = (p["tree"] as? JsonArray).orEmpty()
            val titles = tree.map { (it as? JsonObject)?.text("title") ?: "?" }
            "Create ${titles.size} task${if (titles.size == 1) "" else "s"}: ${titles.joinToString(", ")}"
        }
        "ask" -> p.text("question") ?: "A question"
        "add_dep" -> "Make$item wait for ${p.text("depends_on") ?: "?"}"
        "remove_dep" -> "Stop$item waiting for ${p.text("depends_on") ?: "?"}"
        "run" -> "Run$item" + (p.text("role")?.let { " ($it)" } ?: "")
        "retry" -> "Retry$item" + (p.text("note")?.let { ": $it" } ?: "")
        "complete" -> "Complete the mission"
        else -> kind.replace('_', ' ') + item
    }
}

/** A question card is answered in words; the rest are applied or dismissed. */
val MissionCard.isQuestion: Boolean get() = kind == "ask"

/** The options a question card offers, if it named any. */
val MissionCard.options: List<String>
    get() = (payload?.get("options") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/** Dollars from micro-USD, two places, as the desktop's `dollars`. */
fun dollars(micros: Long): String {
    val cents = (micros + if (micros >= 0) 5_000 else -5_000) / 10_000
    val sign = if (cents < 0) "-" else ""
    val c = abs(cents)
    return "$sign\$${c / 100}.${(c % 100).toString().padStart(2, '0')}"
}

/** What the autonomy level lets the loop do, in words. */
fun autonomyLabel(level: Int): String = when (level) {
    0 -> "keeps the cards only"
    1 -> "proposes; a person presses every step"
    2 -> "runs ready work under a grant"
    else -> "runs and closes work under a grant"
}

/** A mission's progress for a list line: "3/5 done · active". */
fun Mission.summary(): String = buildList {
    if (total > 0) add("$done/$total done")
    add(state)
    if (mode == "continuous") add("continuous")
}.joinToString(" · ")

/** The single-mission moves the phone offers: pause a running one, resume a paused one. */
fun Mission.pauseMove(): String? = when (state) {
    "active" -> "paused"
    "paused" -> "active"
    else -> null
}
