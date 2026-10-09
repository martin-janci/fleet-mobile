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
