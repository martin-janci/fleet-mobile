package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One session Tidy-up suggests acting on (`work { action: tidy }`): why
 * ([reason]), what it suggests ([action]), and what the person may choose
 * instead (see `tidyChoices`).
 */
@Serializable
data class TidyCandidate(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("link_id") val linkId: Long? = null,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("tmux_name") val tmuxName: String = "",
    val kind: String? = null,
    /** done_idle, pr_merged_idle, not_planned, duplicate_worktree, ghost_expiring, idle_unlinked, … */
    val reason: String = "",
    val secondary: List<String> = emptyList(),
    /** archive, safe_kill, kill, resume_or_expire. */
    val action: String = "",
    val since: Long = 0,
    val label: String? = null,
    val key: String? = null,
    @SerialName("item_status") val itemStatus: String? = null,
    val branch: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("idle_secs") val idleSecs: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val archived: Boolean = false,
)

@Serializable
data class TidyReport(
    val candidates: List<TidyCandidate> = emptyList(),
    @SerialName("auto_tidy") val autoTidy: Boolean = false,
)

/** One choice to apply (`work_link { action: tidy_apply, items }`). */
@Serializable
data class TidyApplyItem(
    @SerialName("session_id") val sessionId: Long,
    val action: String,
    @SerialName("link_id") val linkId: Long? = null,
    val days: Int? = null,
)

@Serializable
data class TidyApplyResult(
    @SerialName("session_id") val sessionId: Long,
    val action: String = "",
    val ok: Boolean = false,
    val outcome: String? = null,
    val error: String? = null,
)

@Serializable
data class TidyApplied(val results: List<TidyApplyResult> = emptyList())

/** A work item that came back after it was done (`work { action: reopened }`). */
@Serializable
data class ReopenedWork(
    @SerialName("item_id") val itemId: Long,
    val key: String? = null,
    val title: String = "",
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    @SerialName("reopened_at") val reopenedAt: Long = 0,
    @SerialName("past_sessions") val pastSessions: Int = 0,
    @SerialName("live_sessions") val liveSessions: Int = 0,
    @SerialName("last_host") val lastHost: String? = null,
)

/** `work_link { action: summarize }`: a Claude-written summary of a past session, kept in the work's journal. */
@Serializable
data class PastWorkSummary(
    val key: String = "",
    @SerialName("link_id") val linkId: Long = 0,
    @SerialName("host_alias") val hostAlias: String = "",
    val model: String = "",
    val summary: String = "",
    val truncated: Boolean = false,
)
