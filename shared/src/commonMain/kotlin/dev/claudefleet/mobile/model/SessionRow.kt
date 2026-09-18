package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One session as the hub reports it from `list_sessions` with `summary=false`.
 *
 * The hub's own row (`crates/fleet-core/src/store/rows.rs`) carries about forty
 * columns; this mirrors the ones a phone draws, and `ignoreUnknownKeys` lets the
 * rest — and anything the hub grows later — pass by. Every field but `id` has a
 * default because `list_sessions` serializes through `ok_json_compact`, which
 * strips nulls from the wire entirely rather than sending `"field": null`.
 *
 * `is_controller` is not a column: the tool flattens it onto each row.
 */
@Serializable
data class SessionRow(
    val id: Long,
    @SerialName("tmux_name") val tmuxName: String = "",
    @SerialName("friendly_name") val friendlyName: String? = null,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("worktree_id") val worktreeId: Long? = null,
    /** The tmux-level state: `running`, `stopped`, … */
    val status: String = "",
    /** `work` | `shell` | `review` | … */
    val kind: String? = null,
    /** working | blocked | completed | failed | stopped | idle; null when unknown. */
    @SerialName("claude_status") val claudeStatus: String? = null,
    /** auth_menu | reconnect | trust_prompt | oom | press_enter; null when not stuck. */
    @SerialName("stuck_kind") val stuckKind: String? = null,
    /** The hub's one-line summary of what the session is doing. */
    @SerialName("current_activity") val currentActivity: String? = null,
    @SerialName("context_pct") val contextPct: Double? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    /** Set on a ghost row — a session fleet knows about but tmux no longer has. */
    @SerialName("lost_at") val lostAt: Long? = null,
    @SerialName("turn_seq") val turnSeq: Long = 0,
    @SerialName("pr_url") val prUrl: String? = null,
    /** passing | failing | pending, from the PR's check rollup. */
    @SerialName("ci_status") val ciStatus: String? = null,
    @SerialName("is_controller") val isController: Boolean = false,
    val tags: List<String> = emptyList(),
) {
    /** What the sidebar shows: the agent's own label, else the tmux name. */
    val displayName: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: tmuxName

    /** The rows the "needs attention" filter keeps. */
    val needsAttention: Boolean get() = claudeStatus == "blocked" || stuckKind != null
}
