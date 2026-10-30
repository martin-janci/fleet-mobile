package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether the fleet's coordinator (Control, the desktop's agent) can take a
 * message, as `operator_status` answers it: its session when it has one, and
 * otherwise why not — `absent`, `lost`, `no_mcp`, `token_revoked`, `no_host`
 * or `host_down`.
 */
@Serializable
data class OperatorStatus(
    val ready: Boolean = false,
    val session: SessionRow? = null,
    val blocked: String? = null,
    val host: String? = null,
    /** Under `host_down`: where `ensure_operator` would start Control instead; null when no host qualifies. */
    val fallback: String? = null,
)

/**
 * A call the hub holds for a person's yes (redesign 9.2): Control, or another
 * caller, wants to start or kill a session. Listed by `mcp_confirms`,
 * answered with `answer_mcp_confirm`; [operator] is true for Control's own.
 */
@Serializable
data class ConfirmRequest(
    val nonce: String,
    val tool: String,
    val summary: String = "",
    val caller: String = "",
    val operator: Boolean = false,
    @SerialName("asked_at") val askedAt: Long = 0,
    /** What approving it does, in words; null from an older hub. [summary] stays the exact call. */
    val about: ConfirmAbout? = null,
)

/**
 * A waiting call in words (the hub's `guard::ConfirmAbout`): the card's title,
 * the ticket, host and project by name, and the brief or prompt itself.
 * Display only: the approval binds to [ConfirmRequest.summary].
 */
@Serializable
data class ConfirmAbout(
    val title: String = "",
    val facts: List<ConfirmFact> = emptyList(),
    val body: ConfirmBody? = null,
)

/** One row of [ConfirmAbout.facts]: `Ticket` · `PD-2709 Fix the login`, [url] where it opens. */
@Serializable
data class ConfirmFact(val label: String = "", val value: String = "", val url: String? = null)

/** [ConfirmAbout.body]: `Brief` and its text. */
@Serializable
data class ConfirmBody(val label: String = "", val text: String = "")

/** A work item as a handoff receipt carries it (the hub's `HandoffItem`, redesign 9.3). */
@Serializable
data class HandoffItem(
    val id: Long,
    val title: String = "",
    /** The status category: `todo`, `in_progress`, `done`, … */
    val status: String = "",
    @SerialName("proposal_state") val proposalState: String? = null,
    /** The tree's own edges (the task graph card's waves); empty from an older hub. */
    @SerialName("depends_on") val dependsOn: List<Long> = emptyList(),
)

/**
 * What Control's agent handed on, and where (`control_handoffs`, the hub's
 * `ControlHandoffRow`): a prompt or task to a session, a mission, a task, a
 * proposed tree of subtasks. The target's state is read now: a session's
 * from the live rows, a mission's and a task's from the receipt.
 */
@Serializable
data class ControlHandoff(
    val id: Long,
    val at: Long = 0,
    /** `session` | `mission` | `task` | `tree`. */
    val kind: String = "",
    val tool: String = "",
    @SerialName("session_id") val sessionId: Long? = null,
    @SerialName("mission_id") val missionId: Long? = null,
    @SerialName("mission_name") val missionName: String? = null,
    @SerialName("mission_state") val missionState: String? = null,
    val item: HandoffItem? = null,
    val items: List<HandoffItem> = emptyList(),
    val preview: String? = null,
    /**
     * How the call was let through (claude-fleet migration 196): `person` —
     * approved on its card; `pre_approved` — the autonomy position let it
     * through without a card. Null when no card was needed, or from an
     * older hub (review wave 24, w24-5).
     */
    val approval: String? = null,
)

/** The chip's words for how a handoff was let through, or null — the desktop's `approvalWords` (`handoffs.ts`). */
fun approvalWords(h: ControlHandoff): String? = when (h.approval) {
    "pre_approved" -> "pre-approved"
    "person" -> "approved"
    else -> null
}
