package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `work_link { action: start, project_ids }` answers (claude-fleet M9.6,
 * `service::trackers::tickets::MultiStart`): one sibling session per
 * repository, all on one branch name. Every repository lands in exactly one
 * list, so a partial start is an ordinary answer, not an error — some
 * started, some already ran, some were refused.
 */
@Serializable
data class MultiStart(
    val key: String = "",
    val started: List<SessionRow> = emptyList(),
    val warnings: List<StartWarning> = emptyList(),
    val skipped: List<StartSkip> = emptyList(),
    val failed: List<StartFailure> = emptyList(),
)

/** A session that started but whose link or brief failed: it runs. */
@Serializable
data class StartWarning(
    @SerialName("project_id") val projectId: Long,
    @SerialName("session_id") val sessionId: Long,
    val code: String = "",
    val message: String = "",
)

/**
 * A repository left alone: the key already runs there (naming the session
 * when the hub knows it), or [SKIP_DEADLINE] — the start ran out of time
 * before its turn.
 */
@Serializable
data class StartSkip(
    @SerialName("project_id") val projectId: Long,
    @SerialName("session_id") val sessionId: Long? = null,
    val reason: String = "",
) {
    companion object {
        const val SKIP_DEADLINE = "deadline"
    }
}

/**
 * A repository the hub could not start in. [crossOrg] is the org rule's
 * refusal: the repository and the ticket belong to different organisations.
 * The desktop offers "Start anyway" for it; the phone only says so.
 */
@Serializable
data class StartFailure(
    @SerialName("project_id") val projectId: Long,
    val code: String = "",
    val message: String = "",
    @SerialName("cross_org") val crossOrg: Boolean = false,
)

/**
 * What `work_link { action: preview_start }` answers, the part the phone
 * reads (claude-fleet task → session spec P-1): where a start of the key
 * would land, nothing made. [plan] is null while [missing] names what the
 * hub still needs (`project`, `host`).
 */
@Serializable
data class StartPreview(
    val key: String = "",
    val title: String = "",
    val plan: StartPlan? = null,
    val missing: String? = null,
)

/** The planned start: the repository, the host, and the branch the hub would name from the ticket. */
@Serializable
data class StartPlan(
    @SerialName("project_id") val projectId: Long = 0,
    @SerialName("host_alias") val hostAlias: String = "",
    val branch: String = "",
)
