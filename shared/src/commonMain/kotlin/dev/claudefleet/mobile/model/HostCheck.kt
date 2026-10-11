package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A host's health checklist as the hub's `check_host` answers it
 * (claude-fleet revision 17): one SSH read from the hub of tmux, the agents
 * on PATH and fleet's hooks in `~/.claude/settings.json`. Every field but
 * [alias] and [checkedAt] is null when the host could not be asked, and
 * [error] then says why.
 */
@Serializable
data class HostCheck(
    val alias: String,
    @SerialName("checked_at") val checkedAt: Long = 0,
    val error: String? = null,
    @SerialName("tmux_version") val tmuxVersion: String? = null,
    @SerialName("agents_on_path") val agentsOnPath: List<String>? = null,
    @SerialName("fleet_hooks") val fleetHooks: Boolean? = null,
    @SerialName("guard_hook") val guardHook: Boolean? = null,
)

/** One checklist line: what was checked, and whether it is fine. */
data class HostCheckLine(val label: String, val detail: String, val ok: Boolean)

/** The checklist in the desktop's order; empty when the host did not answer. */
fun HostCheck.lines(): List<HostCheckLine> {
    if (error != null) return emptyList()
    val agents = agentsOnPath.orEmpty()
    return listOf(
        HostCheckLine("tmux", tmuxVersion ?: "not installed", tmuxVersion != null),
        HostCheckLine("Agents on PATH", agents.joinToString(", ").ifEmpty { "none found" }, agents.isNotEmpty()),
        HostCheckLine(
            "Fleet's hooks",
            when (fleetHooks) {
                true -> "installed"
                false -> "missing — provision the host from the hub"
                null -> "no readable ~/.claude/settings.json"
            },
            fleetHooks == true,
        ),
        HostCheckLine(
            "Worker guard",
            when (guardHook) {
                true -> "installed"
                false -> "missing"
                null -> "no readable ~/.claude/settings.json"
            },
            guardHook == true,
        ),
    )
}
