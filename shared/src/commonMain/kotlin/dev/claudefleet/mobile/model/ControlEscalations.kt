package dev.claudefleet.mobile.model

/*
 * What in the fleet needs the person, as amber cards in Control's chat (the
 * desktop's `src/lib/control_escalations.ts`, claude-fleet #921): a session
 * asking a question or for a permission, stuck, failed, out of its
 * account's limit, without a login, or on a host that is down. The rows and
 * the triage are the Inbox's own ([triageBucket]), so a card and Needs you
 * never disagree.
 *
 * Two departures from the desktop, both from the Control review
 * (2026-10-17, F7 and F18): Control's own row is a card when its account is
 * at its limit or signed out (the one place the person would otherwise not
 * learn that Control itself stalled), and Later is kept on this phone across
 * restarts ([EscalationLater]) rather than per window.
 */

/** The buckets that escalate, worst first, and how a card says each. */
private val WHY: List<Pair<TriageBucket, String>> = listOf(
    TriageBucket.WAITING to "is waiting for your answer",
    TriageBucket.HOST_DOWN to "is on a host that is down",
    TriageBucket.STUCK to "is stuck",
    TriageBucket.STOP_FAILED to "stopped with an error",
    TriageBucket.FAILED to "failed",
    TriageBucket.ACCOUNT_LIMIT to "hit its account limit",
    TriageBucket.NO_CREDENTIALS to "needs a login",
)

/** What Control's own row is a card for: what stalls it without its chat saying so. */
private val OWN_BUCKETS: Set<TriageBucket> = setOf(TriageBucket.ACCOUNT_LIMIT, TriageBucket.NO_CREDENTIALS)

private fun why(bucket: TriageBucket): String? = WHY.firstOrNull { it.first == bucket }?.second

private fun rank(bucket: TriageBucket): Int = WHY.indexOfFirst { it.first == bucket }

data class Escalation(
    /**
     * The card's identity: the session, why, and its turn, so a card put off
     * comes back when the same session needs the person again later. A down
     * host is one card keyed by the host.
     */
    val key: String,
    val sessionId: Long,
    val bucket: TriageBucket,
    /** "fed-v2 is waiting for your answer". */
    val line: String,
    /** The question or the command a permission asks for, when the pane shows one. */
    val detail: String? = null,
)

/** How many cards the chat draws; the rest are "N more in Needs you". */
const val ESCALATIONS_SHOWN = 3

fun escalationKey(row: SessionRow, bucket: TriageBucket): String = "${row.id}:${bucket.name.lowercase()}:${row.turnSeq}"

/**
 * What needs the person, worst first (then by id, so the list does not
 * reshuffle on every frame), leaving out the cards put off ([later]).
 * [controlId] is Control's own session: a card only for [OWN_BUCKETS].
 */
fun escalations(rows: List<SessionRow>, controlId: Long?, later: Set<String>): List<Escalation> {
    val out = mutableListOf<Escalation>()
    // A host that is down is one card, not one per session on it; insertion order is kept.
    val down = LinkedHashMap<String, MutableList<SessionRow>>()
    for (row in rows) {
        val bucket = row.triageBucket()
        val words = why(bucket) ?: continue
        val own = row.id == controlId
        if (own && bucket !in OWN_BUCKETS) continue
        if (bucket == TriageBucket.HOST_DOWN) {
            if ("host:${row.hostAlias}" in later) continue
            down.getOrPut(row.hostAlias) { mutableListOf() }.add(row)
            continue
        }
        val key = escalationKey(row, bucket)
        if (key in later) continue
        val p = row.pendingInput
        val detail = if (bucket == TriageBucket.WAITING && p != null) (p.detail ?: p.question)?.takeIf { it.isNotBlank() } else null
        val name = if (own) "Control" else row.displayName
        out += Escalation(key, row.id, bucket, "$name $words", detail)
    }
    for ((host, on) in down) {
        val first = on.first()
        val line = if (on.size == 1) "$host is down: ${first.displayName} waits on it" else "$host is down: ${on.size} sessions wait on it"
        out += Escalation("host:$host", first.id, TriageBucket.HOST_DOWN, line)
    }
    return out.sortedWith(compareBy<Escalation> { rank(it.bucket) }.thenBy { it.sessionId })
}

/** The most Later keys kept; the oldest go first (a key carries a turn, so old ones never match again). */
const val LATER_KEPT = 200

/** [key] put off, on top of [later], the oldest dropped past [LATER_KEPT]. */
fun putOff(later: List<String>, key: String): List<String> = (later.filterNot { it == key } + key).takeLast(LATER_KEPT)
