package dev.claudefleet.mobile.model

/**
 * Triage buckets, most urgent first — the desktop's `TRIAGE_BUCKETS`
 * (`src/lib/attention.ts`), in its order, which *is* the ranking.
 *
 * Everything that orders sessions by urgency reads this one list, on both
 * clients, so the phone and the desktop cannot drift into disagreeing about
 * which session is worst.
 *
 * Two buckets are reachable and stay empty here, as they do on the desktop:
 * [DONE_UNREAD] needs a `last_viewed_at` the hub does not yet stamp, and
 * [IDLE_LONG] only fills when a caller passes a non-zero idle threshold.
 */
enum class TriageBucket(val label: String) {
    WAITING("Waiting for you"),
    STUCK("Stuck"),
    FAILED("Failed"),
    DONE_UNREAD("Done, unread"),
    LIFECYCLE("Lifecycle"),
    IDLE_LONG("Idle a long time"),
    WORKING("Working"),
    IDLE("Idle"),
}

/**
 * Age is capped so no wait, however long, lets a row jump its bucket — the
 * desktop's `AGE_CAP_SECS`. A `Long` here where the desktop uses a JS number:
 * the score multiplies by this, and on Kotlin/Native an `Int` would overflow
 * at eight buckets × a million seconds.
 */
private const val AGE_CAP_SECONDS = 1_000_000L

/**
 * Which bucket this row is in. The order of the checks **is** the bucket
 * order, exactly as the desktop's `classify` has it.
 *
 * A session running outside fleet (`external`) is read-only from here and can
 * never be acted on, so it is [TriageBucket.WORKING] or [TriageBucket.IDLE]
 * whatever its other fields say — it is never something a person is asked to
 * deal with.
 *
 * [idleSeconds] of 0 turns [TriageBucket.IDLE_LONG] off, which is the default
 * and, for now, always the case: the desktop drives it from an operator
 * setting (`attentionIdleMinutes`) that the phone has no screen for yet. The
 * parameter is here so the rule is ported rather than quietly dropped.
 */
fun SessionRow.triageBucket(idleSeconds: Long = 0, now: Long = 0): TriageBucket {
    if (kind == "external") {
        return if (claudeStatus == "working") TriageBucket.WORKING else TriageBucket.IDLE
    }
    if (claudeStatus == "blocked") return TriageBucket.WAITING
    if (stuckKind != null) return TriageBucket.STUCK
    if (claudeStatus == "failed") return TriageBucket.FAILED
    // `done_unread` needs `last_viewed_at`, which no hub stamps yet.
    if (isLifecycleBroken) return TriageBucket.LIFECYCLE
    if (isIdleLong(idleSeconds, now)) return TriageBucket.IDLE_LONG
    if (claudeStatus == "working") return TriageBucket.WORKING
    return TriageBucket.IDLE
}

private val SessionRow.isLifecycleBroken: Boolean
    get() = safeKillState == "failed" || safeKillState == "requested" || status == "ghost" || lostAt != null

private fun SessionRow.isIdleLong(idleSeconds: Long, now: Long): Boolean {
    if (idleSeconds <= 0) return false
    if (kind != "work" && kind != "review") return false
    val since = lastActivityAt ?: return false
    return now - since >= idleSeconds
}

/**
 * When the row entered the state its bucket describes, best effort.
 *
 * The desktop reads `stuck_since`, `idle_since` and `safe_kill_requested_at`,
 * none of which reach the phone's [SessionRow]. What does reach it is the
 * hub's own [Attention.since] — the instant it decided this row wanted a
 * person — which is the same question those columns answer and is stamped by
 * the side that knows. `last_activity_at` is the fallback throughout, as it is
 * there.
 */
private fun SessionRow.bucketSince(bucket: TriageBucket): Long? = when (bucket) {
    TriageBucket.STUCK, TriageBucket.LIFECYCLE -> lostAt ?: attention?.since ?: lastActivityAt
    TriageBucket.DONE_UNREAD -> lastStopAt ?: lastTurnAt ?: lastActivityAt
    TriageBucket.WORKING -> lastActivityAt
    else -> attention?.since ?: lastActivityAt
}

/**
 * Sort weight, higher = more urgent: the bucket dominates and the age only
 * breaks ties within it, which is what the cap guarantees.
 *
 * A row with no usable stamp scores age 0 — the *youngest* of its bucket
 * rather than the oldest. An unstamped row is one nothing is known about, and
 * letting "unknown" sort as "has been waiting forever" would put it above real
 * waits at the top of the queue.
 */
fun SessionRow.triageScore(idleSeconds: Long = 0, now: Long = 0): Long {
    val bucket = triageBucket(idleSeconds, now)
    val since = bucketSince(bucket)
    val age = if (since == null) 0L else (now - since).coerceIn(0L, AGE_CAP_SECONDS - 1)
    return (TriageBucket.entries.size - bucket.ordinal) * AGE_CAP_SECONDS + age
}

/**
 * Worst first: bucket, then the longest wait, then id — the desktop's
 * `byTriage`.
 *
 * The id is not decoration. Without it two rows of equal score swap places
 * every time the list is rebuilt, and this list is rebuilt on every event
 * frame: the tie-break is what stops the queue reshuffling under a thumb.
 */
fun List<SessionRow>.byTriage(idleSeconds: Long = 0, now: Long = 0): List<SessionRow> =
    sortedWith(compareByDescending<SessionRow> { it.triageScore(idleSeconds, now) }.thenBy { it.id })
