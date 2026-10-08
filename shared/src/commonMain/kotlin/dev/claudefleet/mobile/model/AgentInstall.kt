package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One fleet-agent install job (`agent_installs`, claude-fleet 4.9): the hub
 * installs its agent on a host it reaches over SSH, then moves the host onto
 * it. [step] runs `target` → `download` → `start` → `connect` → `done`.
 */
@Serializable
data class AgentInstall(
    val id: Long,
    @SerialName("host_alias") val hostAlias: String,
    val version: String = "",
    /** `running` | `done` | `failed` */
    val state: String = "running",
    /** `target` | `download` | `start` | `connect` | `done` */
    val step: String = "target",
    val detail: String? = null,
    @SerialName("started_at") val startedAt: Long = 0,
    @SerialName("finished_at") val finishedAt: Long? = null,
)
