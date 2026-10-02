package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
    /**
     * Where the hub had this group in its one list, stamped by [scopeToday]
     * when it splits that list into sections.
     *
     * `@Transient` so it is the client's own bookkeeping and not a wire field:
     * a hub that one day sends `hub_index` must not have it read as this, and
     * this must not appear in anything the client serialises back.
     */
    @Transient val hubIndex: Int = 0,
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
    for ((i, g) in today.groups.withIndex()) {
        val sessions = if (org == null) g.sessions else g.sessions.filter { orgOfSession(it) == org }
        if (sessions.isEmpty()) continue
        // The hub's own position, carried so a later re-bucketing can put a
        // group back where the hub had it — see `inOrder`. This split must not
        // reorder: the order it produces is the desktop's, pinned by
        // `StandupFixtureTest`.
        val group = g.copy(sessions = sessions, hubIndex = i)
        when (bucketOf(sessions)) {
            TodayBucket.Waiting -> waiting += group
            TodayBucket.Stale -> stale += group
            TodayBucket.InProgress -> inProgress += group
        }
    }
    val shipped = if (org == null) today.shipped else today.shipped.filter { it.orgId == org }
    return TodayView(waiting, inProgress, shipped, stale)
}

/**
 * Back into the hub's own order, by the position [scopeToday] stamped.
 *
 * `filterToday` re-buckets — the host filter can take away the session that
 * made a group waiting — by walking `waiting + inProgress + stale` and
 * appending, so a group that changed bucket landed at the END of its new
 * section and the order a person saw depended on which section it used to be
 * in. Sorting by the hub's index undoes exactly that, and nothing else: the
 * unfiltered order is the hub's, and turning a filter on now narrows the list
 * without reshuffling what is left.
 *
 * Deliberately NOT an order of the sheet's own devising. The first attempt
 * sorted by newest activity, which read well and broke `StandupFixtureTest`:
 * the unfiltered order is a cross-repo contract with the desktop's standup,
 * and a phone that sorts it differently diverges from the text this sheet
 * exists to copy.
 */
private fun List<TodayGroup>.inOrder(): List<TodayGroup> = sortedBy { it.hubIndex }

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

/**
 * What a group's status reads as: the tracker's own name when it has one, else
 * the category in words — which is the ONLY status a local work item ever has,
 * since the hub leaves `status_name` null for one. Empty when neither is known.
 *
 * The desktop's `groupStatusLabel`, so the standup copies the same line from
 * either and the sheet's pill says the same word.
 */
fun groupStatusLabel(g: TodayGroup): String = g.statusName ?: g.statusCategory?.spoken() ?: ""

/** `KEY title` for a group; the no-work group names nothing itself. */
fun groupLabel(key: String?, title: String): String {
    if (key.isNullOrEmpty()) return "No work"
    val t = title.trim()
    return if (t.isNotEmpty()) "$key $t" else key
}

private fun groupLines(g: TodayGroup, bucket: TodayBucket): List<String> {
    if (g.key.isNullOrEmpty()) return g.sessions.map { "- ${sessionPhrase(it, bucket)}" }
    val extras = buildList {
        // Through `groupStatusLabel`, which falls back to the category: the hub
        // leaves `status_name` null for every LOCAL work item, so reading it
        // alone dropped the status from the standup for exactly the work this
        // sheet is mostly about — and the desktop's line, which this is
        // promised to match, has always carried it.
        groupStatusLabel(g).takeIf { it.isNotEmpty() }?.let { add(it) }
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

/** A section of the Today sheet, as its filter chips name them. */
enum class TodaySection(val label: String) {
    Waiting("Waiting on me"),
    InProgress("In progress"),
    Shipped("Shipped"),
    Stale("Stale"),
}

/**
 * What the Today sheet's chips narrow the digest to — the phone's, on top of
 * the Sessions list's org filter ([scopeToday]); the desktop has no copy.
 * Empty [sections] shows every section, as *Any* does in the Sessions filters.
 */
data class TodayFilters(
    val sections: Set<TodaySection> = emptySet(),
    /** One host's sessions; null keeps every host. */
    val host: String? = null,
    /** Hide the sessions no ticket is linked to (the no-work groups). */
    val ticketsOnly: Boolean = false,
) {
    val any: Boolean get() = sections.isNotEmpty() || host != null || ticketsOnly
}

/**
 * [v] narrowed by [f]. The host filter drops sessions, so groups are
 * re-bucketed with the hub's rule ([bucketOf]), as [scopeToday] does after
 * the org filter: a group waiting only because of a session on another host
 * is in progress here. Shipped entries carry no host, so the host filter
 * leaves them alone — the sheet says so on the section.
 *
 * Each section comes back [inOrder], the same rule [scopeToday] applies, so
 * turning a filter on narrows the list without reshuffling what is left.
 */
fun filterToday(v: TodayView, f: TodayFilters): TodayView {
    val waiting = mutableListOf<TodayGroup>()
    val inProgress = mutableListOf<TodayGroup>()
    val stale = mutableListOf<TodayGroup>()
    for (g in v.waiting + v.inProgress + v.stale) {
        if (f.ticketsOnly && g.key.isNullOrEmpty()) continue
        val sessions = if (f.host == null) g.sessions else g.sessions.filter { it.hostAlias == f.host }
        if (sessions.isEmpty()) continue
        val group = if (sessions.size == g.sessions.size) g else g.copy(sessions = sessions)
        when (bucketOf(sessions)) {
            TodayBucket.Waiting -> waiting += group
            TodayBucket.InProgress -> inProgress += group
            TodayBucket.Stale -> stale += group
        }
    }
    fun keep(s: TodaySection) = f.sections.isEmpty() || s in f.sections
    return TodayView(
        waiting = if (keep(TodaySection.Waiting)) waiting.inOrder() else emptyList(),
        inProgress = if (keep(TodaySection.InProgress)) inProgress.inOrder() else emptyList(),
        shipped = if (keep(TodaySection.Shipped)) v.shipped else emptyList(),
        stale = if (keep(TodaySection.Stale)) stale.inOrder() else emptyList(),
    )
}

/**
 * How many ROWS a section's card lists: a group for Waiting / In progress /
 * Stale (a no-work group counts once), a line for Shipped.
 *
 * The chips and the section headers both read this, so the two always agree —
 * but a "Waiting 3" chip counts three pieces of work, which may be any number
 * of sessions, while "Shipped 5" counts five lines. That is what the card
 * draws, which is what a count beside a heading means; [TodayView.sessions] is
 * the number of sessions, and the header line is where that is said.
 */
fun TodayView.count(s: TodaySection): Int = when (s) {
    TodaySection.Waiting -> waiting.size
    TodaySection.InProgress -> inProgress.size
    TodaySection.Shipped -> shipped.size
    TodaySection.Stale -> stale.size
}

/** Every session the view lists, once each. */
val TodayView.sessions: List<TodaySession>
    get() = (waiting + inProgress + stale).flatMap { it.sessions }.distinctBy { it.id }

/** The hosts the view's sessions run on, sorted — the sheet's host chips. */
fun todayHosts(v: TodayView): List<String> = v.sessions.map { it.hostAlias }.filter { it.isNotEmpty() }.distinct().sorted()

private val ATTENTION_LABELS = mapOf(
    "waiting" to "Waiting for you",
    "stuck" to "Stuck",
    "stop_failed" to "Stop failed",
    "failed" to "Turn failed",
    "context_full" to "Context full",
    "stale_working" to "Stalled",
    "ci_failing" to "CI failing",
    "lifecycle" to "Needs a look",
)

/**
 * The words on a session's attention chip. The hub's reasons
 * (`attention.rs`'s `Reason`) are wire tokens — `ci_failing` must never
 * reach a screen as it is, and an unknown one reads as words rather than
 * snake case. The standup keeps [sessionPhrase]'s words, the desktop's,
 * byte for byte; this is the sheet's alone.
 */
fun attentionLabel(reason: String): String =
    ATTENTION_LABELS[reason] ?: reason.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

/** The words on a stale session's chip: why it is listed under Stale. */
fun staleLabel(stale: String): String = if (stale == "done") "Ticket done" else "Idle"

/** Whether any group is the no-work one — what *Tickets only* would hide. */
val TodayView.hasUnlinked: Boolean get() = (waiting + inProgress + stale).any { it.key.isNullOrEmpty() }
