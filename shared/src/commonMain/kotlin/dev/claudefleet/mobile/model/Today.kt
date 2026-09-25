package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Today digest, `work { action: today, since }` (claude-fleet M9.1):
 * the sessions active since [since] grouped by their work, and what shipped.
 *
 * Mirrors `Today` in the hub's `service/work/today.rs`. The hub buckets each
 * group; the phone re-buckets after its org filter drops sessions, with the
 * hub's own rule ([bucketOf]). Every title is the tracker's text.
 */
@Serializable
data class Today(
    val since: Long = 0,
    val now: Long = 0,
    val groups: List<TodayGroup> = emptyList(),
    val shipped: List<TodayShipped> = emptyList(),
)

@Serializable
data class TodayGroup(
    /** `waiting` | `in_progress` | `stale` — the hub's; see [bucketOf]. */
    val bucket: String = "",
    /** Null for the sessions with no work, which the hub groups per bucket. */
    val key: String? = null,
    @SerialName("item_id") val itemId: Long? = null,
    val title: String = "",
    @SerialName("status_category") val statusCategory: StatusCategory? = null,
    @SerialName("status_name") val statusName: String? = null,
    val url: String? = null,
    @SerialName("org_id") val orgId: Long? = null,
    val sessions: List<TodaySession> = emptyList(),
)

@Serializable
data class TodaySession(
    val id: Long,
    /** The friendly name, else the tmux name — the hub picks. */
    val name: String = "",
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
    @SerialName("org_id") val orgId: Long? = null,
    /** Why it needs a person: `waiting` | `stuck` | `failed` | `lifecycle`; null when nobody is needed. */
    val attention: String? = null,
    /** `idle` (quiet for days) or `done` (its ticket is done, the session still running). */
    val stale: String? = null,
    @SerialName("claude_status") val claudeStatus: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    @SerialName("ci_status") val ciStatus: String? = null,
)

@Serializable
data class TodayShipped(
    /** `done` (the ticket moved to done) or `pr` (a PR from ended work). */
    val how: String = "",
    val key: String? = null,
    val title: String = "",
    val url: String? = null,
    @SerialName("pr_url") val prUrl: String? = null,
    val at: Long = 0,
    @SerialName("org_id") val orgId: Long? = null,
)

/** A Today group's bucket, after the phone's org filter. */
enum class TodayBucket { Waiting, InProgress, Stale }

/** What the Today sheet draws: the four sections, in the desktop's order. */
data class TodayView(
    val waiting: List<TodayGroup> = emptyList(),
    val inProgress: List<TodayGroup> = emptyList(),
    val shipped: List<TodayShipped> = emptyList(),
    val stale: List<TodayGroup> = emptyList(),
) {
    val isEmpty: Boolean get() = waiting.isEmpty() && inProgress.isEmpty() && shipped.isEmpty() && stale.isEmpty()
}

/**
 * Local midnight of [nowSeconds] in unix seconds, for a clock [utcOffsetSeconds]
 * ahead of UTC: "today" is the viewer's, as on the desktop. Pure, so the
 * arithmetic is tested without a time zone; the offset comes from the platform.
 */
fun localMidnight(nowSeconds: Long, utcOffsetSeconds: Int): Long {
    val local = nowSeconds + utcOffsetSeconds
    return local - local.mod(DAY_SECONDS) - utcOffsetSeconds
}

private const val DAY_SECONDS = 86_400L

/** The hub's rule (`today.rs`), re-applied after the org filter drops sessions. */
fun bucketOf(sessions: List<TodaySession>): TodayBucket = when {
    sessions.any { it.attention != null } -> TodayBucket.Waiting
    sessions.isNotEmpty() && sessions.all { it.stale != null } -> TodayBucket.Stale
    else -> TodayBucket.InProgress
}

/**
 * Cut the digest to [org] — null keeps everything — the way the Sessions
 * list's org filter does: a session by [orgOfSession] (its live row's org,
 * which falls back to the digest's own `org_id` for a session the phone has
 * no row for), a shipped entry by its `org_id`. Groups left empty are dropped
 * and the rest re-bucketed. The desktop's `scopeToday`, with an org id where
 * the desktop has a scope id.
 */
fun scopeToday(today: Today, org: Long?, orgOfSession: (TodaySession) -> Long?): TodayView {
    val waiting = mutableListOf<TodayGroup>()
    val inProgress = mutableListOf<TodayGroup>()
    val stale = mutableListOf<TodayGroup>()
    for (g in today.groups) {
        val sessions = if (org == null) g.sessions else g.sessions.filter { orgOfSession(it) == org }
        if (sessions.isEmpty()) continue
        val group = g.copy(sessions = sessions)
        when (bucketOf(sessions)) {
            TodayBucket.Waiting -> waiting += group
            TodayBucket.Stale -> stale += group
            TodayBucket.InProgress -> inProgress += group
        }
    }
    val shipped = if (org == null) today.shipped else today.shipped.filter { it.orgId == org }
    return TodayView(waiting, inProgress, shipped, stale)
}

private val ATTENTION_WORDS = mapOf(
    "waiting" to "waiting for an answer",
    "stuck" to "stuck",
    "failed" to "turn failed",
    "lifecycle" to "needs a look",
)

/** How a session reads in a line: its name, and why it is listed. */
fun sessionPhrase(s: TodaySession, bucket: TodayBucket): String = when {
    bucket == TodayBucket.Waiting && s.attention != null -> "${s.name} (${ATTENTION_WORDS[s.attention] ?: s.attention})"
    bucket == TodayBucket.Stale && s.stale != null ->
        "${s.name} (${if (s.stale == "done") "ticket done, session still running" else "idle"})"
    else -> s.name
}

/** `KEY title` for a group; the no-work group names nothing itself. */
fun groupLabel(key: String?, title: String): String {
    if (key.isNullOrEmpty()) return "No work"
    val t = title.trim()
    return if (t.isNotEmpty()) "$key $t" else key
}

private fun groupLines(g: TodayGroup, bucket: TodayBucket): List<String> {
    if (g.key.isNullOrEmpty()) return g.sessions.map { "- ${sessionPhrase(it, bucket)}" }
    val extras = buildList {
        g.statusName?.let { add(it) }
        g.sessions.firstOrNull { it.prUrl != null }?.let { s ->
            add(if (s.ciStatus != null) "PR ${s.prUrl} (CI ${s.ciStatus})" else "PR ${s.prUrl}")
        }
    }
    val names = g.sessions.joinToString(", ") { sessionPhrase(it, bucket) }
    val tail = (extras + names).filter { it.isNotEmpty() }.joinToString(" · ")
    return listOf("- ${groupLabel(g.key, g.title)}" + if (tail.isNotEmpty()) " — $tail" else "")
}

private fun shippedLine(x: TodayShipped): String {
    val label = if (!x.key.isNullOrEmpty()) groupLabel(x.key, x.title) else x.title.ifEmpty { "Untitled work" }
    val what = if (x.how == "done") "done" else "PR"
    val link = x.prUrl ?: x.url ?: ""
    return "- $label — $what" + if (link.isNotEmpty()) " $link" else ""
}

/**
 * The standup as plain text: Shipped, In progress, Waiting on me, Stale —
 * each only when it has something. The desktop's `standupText`, line for
 * line, so the same digest copies the same text from either. Tracker titles
 * are copied as they are: the clipboard is the person's own, not an agent's.
 */
fun standupText(v: TodayView): String {
    val parts = mutableListOf<String>()
    fun section(title: String, lines: List<String>) {
        if (lines.isNotEmpty()) parts += (listOf(title) + lines).joinToString("\n")
    }
    section("Shipped", v.shipped.map(::shippedLine))
    section("In progress", v.inProgress.flatMap { groupLines(it, TodayBucket.InProgress) })
    section("Waiting on me", v.waiting.flatMap { groupLines(it, TodayBucket.Waiting) })
    section("Stale", v.stale.flatMap { groupLines(it, TodayBucket.Stale) })
    return if (parts.isNotEmpty()) parts.joinToString("\n\n") + "\n" else "Nothing to report.\n"
}
