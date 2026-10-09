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
)

/** A work item as a handoff receipt carries it (the hub's `HandoffItem`, redesign 9.3). */
@Serializable
data class HandoffItem(
    val id: Long,
    val title: String = "",
    /** The status category: `todo`, `in_progress`, `done`, … */
    val status: String = "",
    @SerialName("proposal_state") val proposalState: String? = null,
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
)
