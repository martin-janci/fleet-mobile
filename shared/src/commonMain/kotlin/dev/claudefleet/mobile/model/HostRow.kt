package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One machine in the fleet, as `list_hosts` reports it. */
@Serializable
data class HostRow(
    val alias: String,
    @SerialName("ssh_alias") val sshAlias: String? = null,
    val reachable: Boolean = false,
    @SerialName("claude_version") val claudeVersion: String? = null,
    @SerialName("tmux_version") val tmuxVersion: String? = null,
    val hidden: Boolean = false,
    @SerialName("last_pinged_at") val lastPingedAt: Long? = null,
    @SerialName("account_uuid") val accountUuid: String? = null,
    val provisioned: Boolean = false,
    /** `ssh` | `agent` — how the hub reaches this host. */
    val transport: String = "ssh",
)
