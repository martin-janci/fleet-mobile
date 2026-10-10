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
    /**
     * Probe facts (claude-fleet Orbit Fleet 4.6, migration 123): the round
     * trip of an empty command in ms (null for `local`, which is not timed),
     * free space under the host's `$HOME` in kB, online CPUs and physical
     * memory in kB. Null when never sampled or from an older hub.
     */
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("disk_home_free_kb") val diskHomeFreeKb: Long? = null,
    @SerialName("cpu_count") val cpuCount: Int? = null,
    @SerialName("mem_total_kb") val memTotalKb: Long? = null,
)
