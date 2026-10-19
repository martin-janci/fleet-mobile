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
    /**
     * The rest of the hub's health sample (`list_hosts`, and every
     * `host:pinged` frame's `health`): `$HOME`'s size and available memory
     * in kB, and when it was sampled. Null when never sampled.
     */
    @SerialName("disk_home_total_kb") val diskHomeTotalKb: Long? = null,
    @SerialName("mem_avail_kb") val memAvailKb: Long? = null,
    @SerialName("health_at") val healthAt: Long? = null,
    /**
     * Host load (claude-fleet migration 193): CPU busy percent over the last
     * interval and memory pressure 0–100, null when the host cannot tell;
     * and the hub's two judgments, made when the sample was written. An
     * older hub omits all four, which reads as unknown and not loaded. See
     * [hostLoad].
     */
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("mem_pressure") val memPressure: Double? = null,
    @SerialName("mem_low") val memLow: Boolean = false,
    @SerialName("cpu_saturated") val cpuSaturated: Boolean = false,
)

/**
 * The health half of a `host:pinged` frame (claude-fleet's `HostHealth`):
 * the sample a probe pass took, which moves every pass and so never costs
 * a full `host:probed` row.
 */
@Serializable
data class HostHealthPing(
    @SerialName("disk_home_free_kb") val diskHomeFreeKb: Long? = null,
    @SerialName("disk_home_total_kb") val diskHomeTotalKb: Long? = null,
    @SerialName("mem_avail_kb") val memAvailKb: Long? = null,
    @SerialName("mem_total_kb") val memTotalKb: Long? = null,
    @SerialName("health_at") val healthAt: Long? = null,
    @SerialName("cpu_pct") val cpuPct: Double? = null,
    @SerialName("mem_pressure") val memPressure: Double? = null,
    @SerialName("mem_low") val memLow: Boolean = false,
    @SerialName("cpu_saturated") val cpuSaturated: Boolean = false,
)

/** [this] with [h]'s sample laid over it; a number the ping leaves out keeps the row's. */
fun HostRow.withHealth(h: HostHealthPing): HostRow = copy(
    diskHomeFreeKb = h.diskHomeFreeKb ?: diskHomeFreeKb,
    diskHomeTotalKb = h.diskHomeTotalKb ?: diskHomeTotalKb,
    memAvailKb = h.memAvailKb ?: memAvailKb,
    memTotalKb = h.memTotalKb ?: memTotalKb,
    healthAt = h.healthAt ?: healthAt,
    cpuPct = h.cpuPct ?: cpuPct,
    memPressure = h.memPressure ?: memPressure,
    memLow = h.memLow,
    cpuSaturated = h.cpuSaturated,
)

/** Never reached and never probed: "not checked yet", not offline (the Hosts list's own rule; review w23-13). */
val HostRow.isNeverChecked: Boolean
    get() = lastPingedAt == null && claudeVersion == null && tmuxVersion == null

/** Lost: the hub had it once and cannot reach it now. A host never checked is not offline. */
val HostRow.isSignalLost: Boolean
    get() = !reachable && !isNeverChecked
