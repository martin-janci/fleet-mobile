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
    @SerialName("last_prompt") val lastPrompt: String? = null,
    @SerialName("last_stop_at") val lastStopAt: Long? = null,
    @SerialName("last_turn_at") val lastTurnAt: Long? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("usage_cost_micros") val usageCostMicros: Long? = null,
    @SerialName("usage_model") val usageModel: String? = null,
    @SerialName("parent_session_id") val parentSessionId: Long? = null,
    val branch: String? = null,
) {
    val isBackground: Boolean get() = tmuxName.startsWith("bg:")

    /** The agent's own label; for a background agent its prompt; else the tmux name. */
    val displayName: String
        get() {
            friendlyName?.takeIf { it.isNotBlank() }?.let { return it }
            if (!isBackground) return tmuxName
            lastPrompt?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
            return "Background · ${tmuxName.removePrefix("bg:").take(4)}"
        }

    /** The rows the "needs attention" filter keeps. */
    val needsAttention: Boolean get() = claudeStatus == "blocked" || stuckKind != null

    /**
     * The row's second line: the sanitised activity when there is one, else
     * how long ago the session did anything and what kind of session it is.
     */
    fun supportingLine(nowSeconds: Long): String? {
        Activity.sanitize(currentActivity)?.let { return it }
        val age = relativeTime(lastActivityAt, nowSeconds)
        val kindLabel = kind?.takeIf { it != "work" && it.isNotBlank() }
        return listOfNotNull(age, kindLabel).joinToString(" · ").ifEmpty { null }
    }
}
