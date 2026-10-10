package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One pull request, as Work › Pull requests lists it (claude-fleet redesign
 * 6.4, `prs { list }`, contract revision 12): every PR a session's branch has
 * had, recorded by the hub's reconcile from the same `gh pr view` probe that
 * feeds a session's PR chip, and kept once the session is gone.
 *
 * The hub always sends `id`, `url`, `state`, `first_seen_at` and
 * `updated_at`; everything else may be absent (an older `gh` answers only the
 * basic fields). Every field but [id] has a default, so one odd row degrades
 * to a bare URL rather than failing the list.
 *
 * [state] stays a string: a state this build has never heard of reads as
 * Open, never as Merged.
 */
@Serializable
data class PullRequest(
    val id: Long,
    val url: String = "",
    /** `owner/name`, from the URL. */
    val repo: String? = null,
    val number: Long? = null,
    /** Third-party text: plain text only. */
    val title: String? = null,
    @SerialName("head_ref") val headRef: String? = null,
    /** `OPEN` | `CLOSED` | `MERGED`. */
    val state: String = "",
    val draft: Boolean = false,
    /** `passing` | `failing` | `pending`; null without checks. */
    @SerialName("ci_status") val ciStatus: String? = null,
    @SerialName("review_decision") val reviewDecision: String? = null,
    @SerialName("merge_state") val mergeState: String? = null,
    /** Unix seconds. */
    @SerialName("merged_at") val mergedAt: Long? = null,
    /** The session that opened it (the first one seen on it). It may be gone. */
    @SerialName("session_id") val sessionId: Long? = null,
    /** The tmux name, kept after the session. */
    @SerialName("session_name") val sessionName: String? = null,
    @SerialName("host_alias") val hostAlias: String? = null,
    @SerialName("project_id") val projectId: Long? = null,
    @SerialName("first_seen_at") val firstSeenAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** One page of `prs { list }`: the rows, newest first, and how many match in all. */
@Serializable
data class PullRequestList(
    val items: List<PullRequest> = emptyList(),
    val total: Int = 0,
)

/** The sheet's filter, and the `state` it sends. Open first, as on the desktop. */
enum class PrFilter(val label: String, val wire: String) {
    OPEN("Open", "open"),
    MERGED("Merged", "merged"),
    CLOSED("Closed", "closed"),
    ALL("All", "all"),
}

/** What a PR's state reads as. A draft is a state of its own here. */
fun prStateLabel(pr: PullRequest): String = when (pr.state.uppercase()) {
    "MERGED" -> "Merged"
    "CLOSED" -> "Closed"
    else -> if (pr.draft) "Draft" else "Open"
}

/** "owner/repo#12", or as much of it as the hub knows; "PR" when it knows neither. */
fun prRef(pr: PullRequest): String = when {
    pr.repo != null && pr.number != null -> "${pr.repo}#${pr.number}"
    pr.number != null -> "#${pr.number}"
    pr.repo != null -> pr.repo
    else -> "PR"
}

/** One short line for CI and review ("CI failing · Changes requested"), or "" when neither is known. */
fun prChecksLabel(pr: PullRequest): String = listOfNotNull(
    when (pr.ciStatus) {
        "passing" -> "CI passing"
        "failing" -> "CI failing"
        "pending" -> "CI running"
        else -> null
    },
    when (pr.reviewDecision) {
        "APPROVED" -> "Approved"
        "CHANGES_REQUESTED" -> "Changes requested"
        "REVIEW_REQUIRED" -> "Review required"
        else -> null
    },
).joinToString(" · ")

/**
 * The check rollup the hub's PR probe counted (claude-fleet
 * `outcome::CheckSummary`). [failing] names at most a few; [failingTotal]
 * counts them all.
 */
@Serializable
data class CheckSummary(
    val total: Int = 0,
    val pending: Int = 0,
    /** Skipped or neutral: neither a pass nor a blocker. */
    val skipped: Int = 0,
    val failing: List<FailingCheck> = emptyList(),
    @SerialName("failing_total") val failingTotal: Int = 0,
)

/** One failing check, by the name GitHub shows. */
@Serializable
data class FailingCheck(val name: String, val url: String? = null)

/**
 * What the PR probe last read as evidence about a session's PR (claude-fleet
 * `outcome::PrEvidence`, a session row's `pr_evidence`). Only the checks are
 * read here; every other key passes by.
 */
@Serializable
data class PrEvidence(
    val checks: CheckSummary = CheckSummary(),
)

/**
 * Details' check count (MobileSession: "15/15 checks"): passed of those that
 * count (skipped ones do not), then what is still running or failing. Null
 * without checks: "no checks" is not a pass, and the CI fact already says
 * what the hub knows.
 */
fun checksLabel(c: CheckSummary): String? {
    val counted = c.total - c.skipped
    if (counted <= 0) return null
    val passed = (counted - c.pending - c.failingTotal).coerceAtLeast(0)
    return listOfNotNull(
        "$passed/$counted checks",
        c.failingTotal.takeIf { it > 0 }?.let { "$it failing" },
        c.pending.takeIf { it > 0 }?.let { "$it running" },
    ).joinToString(" · ")
}
