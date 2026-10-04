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
    STOP_FAILED("Stop failed"),
    FAILED("Failed"),
    CONTEXT_FULL("Context full"),
    STALE_WORKING("Stalled"),
    CI_FAILING("CI failing"),
    DONE_UNREAD("Done, unread"),
    LIFECYCLE("Needs a decision"),
    IDLE_LONG("Idle a long time"),
    WORKING("Working"),
    IDLE("Idle"),
    ;

    /** The buckets a person is asked to deal with — the desktop's `NEEDS_YOU_BUCKETS`. */
    val needsYou: Boolean get() = ordinal <= IDLE_LONG.ordinal
}

/**
 * Age is capped so no wait, however long, lets a row jump its bucket — the
 * desktop's `AGE_CAP_SECS`. A `Long` here where the desktop uses a JS number:
 * the score multiplies by this, and on Kotlin/Native an `Int` would overflow
 * at twelve buckets × a million seconds.
 */
private const val AGE_CAP_SECONDS = 1_000_000L

/**
 * The context percentage the desktop calls critical by default
 * (`contextRedPct`, `health.context_red_pct`): past it a live session's
 * context is full.
 */
internal const val CONTEXT_RED_PCT: Double = 85.0

/**
 * Which bucket this row is in. The order of the checks **is** the bucket
 * order, as the desktop's `classify` has it.
 *
 * The hub's own verdict comes first: a row it stamped with
 * [SessionRow.attention] is in the bucket of that reason, because the hub
 * reads fields the phone never receives (`stale_working_at`, `idle_since`).
 * The local rules are the fallback, for a hub too old to stamp — the same
 * rules, minus the ones only those fields can answer.
 *
 * A session running outside fleet (`external`) or a shell is never something
 * a person is asked to deal with, whatever its other fields say.
 */
fun SessionRow.triageBucket(idleSeconds: Long = 0, now: Long = 0): TriageBucket {
    if (kind == "external") {
        return if (claudeStatus == "working") TriageBucket.WORKING else TriageBucket.IDLE
    }
    if (kind == "shell") return TriageBucket.IDLE
    attention?.reason?.let { reason -> bucketOfReason(reason)?.let { return it } }
    if (claudeStatus == "blocked") return TriageBucket.WAITING
    if (stuckKind != null) return TriageBucket.STUCK
    if (claudeStatus == "failed") return if (kind == "bg") TriageBucket.FAILED else TriageBucket.STOP_FAILED
    val live = status != "ghost" && lostAt == null
    if (live && (contextPct ?: 0.0) >= CONTEXT_RED_PCT) return TriageBucket.CONTEXT_FULL
    if (ciStatus == "failing" && claudeStatus in IDLE_STATUSES) return TriageBucket.CI_FAILING
    // `done_unread` needs `last_viewed_at`, which no hub stamps yet.
    if (isLifecycleBroken) return TriageBucket.LIFECYCLE
    if (isIdleLong(idleSeconds, now)) return TriageBucket.IDLE_LONG
    if (claudeStatus == "working") return TriageBucket.WORKING
    return TriageBucket.IDLE
}

private val IDLE_STATUSES = setOf("idle", "completed", "stopped")

/** The bucket of a hub attention reason, or null for one this build does not rank. */
private fun bucketOfReason(reason: String): TriageBucket? = when (reason) {
    "waiting" -> TriageBucket.WAITING
    "stuck" -> TriageBucket.STUCK
    "stop_failed" -> TriageBucket.STOP_FAILED
    "failed" -> TriageBucket.FAILED
    "context_full" -> TriageBucket.CONTEXT_FULL
    "stale_working" -> TriageBucket.STALE_WORKING
    "ci_failing" -> TriageBucket.CI_FAILING
    "lifecycle" -> TriageBucket.LIFECYCLE
    else -> null
}

/**
 * The words for a hub attention reason, everywhere one is shown — the row,
 * the session's strip, notifications, Today. One table, so a state is never
 * "blocked" on one screen and "Waiting for you" on the next.
 */
fun reasonLabel(reason: String): String =
    bucketOfReason(reason)?.label ?: reason.replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }

private val SessionRow.isLifecycleBroken: Boolean
    get() = safeKillState == "failed" || safeKillState == "requested" || status == "ghost" || lostAt != null

private fun SessionRow.isIdleLong(idleSeconds: Long, now: Long): Boolean {
    if (idleSeconds <= 0) return false
    if (kind != "work" && kind != "review") return false
    val since = lastActivityAt ?: return false
    return now - since >= idleSeconds
}

/**
 * When the row entered the state its bucket describes, best effort: the hub's
 * [Attention.since] where it stamped one (the instant it decided this row
 * wanted a person), else the nearest field the phone has.
 */
private fun SessionRow.bucketSince(bucket: TriageBucket): Long? = when (bucket) {
    TriageBucket.STUCK, TriageBucket.LIFECYCLE -> lostAt ?: attention?.since ?: lastActivityAt
    TriageBucket.STOP_FAILED -> attention?.since ?: lastStopAt ?: lastActivityAt
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
