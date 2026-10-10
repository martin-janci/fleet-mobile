package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One lost session in a restore plan (`restore_host_sessions`): what would be done and why. */
@Serializable
data class RestorePlanEntry(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("tmux_name") val tmuxName: String? = null,
    val cwd: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    @SerialName("friendly_name") val friendlyName: String? = null,
    /** restore, skip, … — the hub's word. */
    val action: String = "",
    val reason: String? = null,
) {
    val name: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: tmuxName ?: "#$sessionId"
    val restores: Boolean get() = action == "restore"
}

/** What one restore did. */
@Serializable
data class RestoreOutcome(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("tmux_name") val tmuxName: String = "",
    val ok: Boolean = false,
    val error: String? = null,
)

/** `restore_host_sessions`: the plan (a dry run) or the plan and its results. */
@Serializable
data class RestoreReport(
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("dry_run") val dryRun: Boolean = true,
    val plan: List<RestorePlanEntry> = emptyList(),
    val results: List<RestoreOutcome> = emptyList(),
)

/**
 * A Claude conversation on a host that fleet has no live pane for
 * (`discover_lost_sessions`). [resumable] is true only when a pane would
 * start in exactly the transcript's directory; anywhere else `claude
 * --resume` starts an empty conversation, so it is not offered.
 */
@Serializable
data class LostCandidate(
    val cwd: String = "",
    @SerialName("git_branch") val gitBranch: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String,
    @SerialName("transcript_mtime") val transcriptMtime: Long = 0,
    @SerialName("derived_tmux_name") val derivedTmuxName: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("worktree_id") val worktreeId: Long? = null,
    /** A fleet row (live or lost) already holds it: restore handles that one. */
    @SerialName("existing_session_id") val existingSessionId: Long? = null,
    /** before_boot | after_boot | stale | unknown. */
    @SerialName("rank_hint") val rankHint: String = "",
    val resumable: Boolean = false,
)

/** What `repair_session` found and did to a session's workspace. */
@Serializable
data class RepairReport(
    val healthy: Boolean = false,
    val actions: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** What it would not do on its own and left for a person. */
    val deferred: List<String> = emptyList(),
    @SerialName("needs_explicit_repair") val needsExplicitRepair: Boolean = false,
)

/**
 * `new_bg_session`'s optional arguments (hub contract 14, redesign 14.16):
 * the project to start in, read-only (no Edit, Write, commit or push), and a
 * stop after so many seconds or dollars. [agent] is sent only when it is not
 * Claude, and only to a hub whose `tools/list` enumerates it
 * ([backgroundAgents]): today's hub refuses a Codex background agent.
 * Unset fields are not sent.
 */
data class BackgroundOptions(
    val projectId: Long? = null,
    /** `codex`; null is Claude Code, the hub's default, and is not sent. */
    val agent: String? = null,
    val readOnly: Boolean = false,
    /** 60 s to 7 days. */
    val stopAfterSecs: Long? = null,
    /** Above 0, at most 1000. */
    val stopAfterUsd: Double? = null,
)

/** `new_bg_session`: the headless session's Claude id, and its fleet row once reconcile matched it. */
@Serializable
data class NewBgSessionResult(
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    val warning: String? = null,
    val session: SessionRow? = null,
)

/** A background agent's agents, by the hub's word (`new_bg_session { agent }`). */
const val BG_AGENT_CLAUDE = "claude"
const val BG_AGENT_CODEX = "codex"

/**
 * PURE: the agents the background agent sheet offers (MobileMissions,
 * "Agent: Claude Code / Codex"), from what the hub's `tools/list`
 * enumerates for `new_bg_session`'s `agent` ([enumerated]): Claude Code
 * always, Codex only when listed. A hub that lists nothing offers Claude alone.
 */
fun backgroundAgents(enumerated: Set<String>?): List<String> =
    listOf(BG_AGENT_CLAUDE) + listOf(BG_AGENT_CODEX).filter { enumerated?.contains(it) == true }

/** An agent as the sheet names it: "Claude Code", "Codex". */
fun backgroundAgentWord(agent: String): String = when (agent) {
    BG_AGENT_CODEX -> "Codex"
    BG_AGENT_CLAUDE -> "Claude Code"
    else -> agent
}
