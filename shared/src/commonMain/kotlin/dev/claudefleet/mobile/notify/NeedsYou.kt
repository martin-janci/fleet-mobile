package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.fingerprint
import dev.claudefleet.mobile.model.reasonLabel
import dev.claudefleet.mobile.ui.inboxRowActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/** What a look at the fleet tells the notifier: a session to announce, or one to stop announcing. */
sealed interface NeedsYouEvent

/**
 * One "a session needs you" notification: which session, its two lines, and
 * what it is doing. [reason] is the hub's attention reason, which decides the
 * notification's kind ([notifyKindOf]) for This phone's switches.
 */
data class NeedsYouAlert(
    val sessionId: Long,
    val title: String,
    val text: String,
    val detail: String? = null,
    val reason: String? = null,
    /**
     * The question the session asks, as the agent put it ("Allow Bash: git
     * push …?") — the notification carries the question, never an answer to
     * it (redesign 14.8). Null when it asks nothing in words.
     */
    val question: String? = null,
    /**
     * An update of the alert already up for this session — its question
     * arrived after the status did — rather than news: the notification is
     * replaced without a second sound or buzz.
     */
    val quiet: Boolean = false,
    /**
     * A failure the Inbox's Retry would send again (its last prompt, the
     * session not the controller's): the notification offers Retry beside
     * Open log. Whether this device may write is the app's check, on arrival.
     */
    val retryable: Boolean = false,
    /**
     * The notification's first line when the alert is about a fact, not one
     * session ("pine is down", review w11-2); null for a session's own alert,
     * which reads "<session> needs you".
     */
    val headline: String? = null,
) : NeedsYouEvent

/** A session that needed you no longer does — answered here or elsewhere: its notification goes. */
data class NeedsYouResolved(val sessionId: Long) : NeedsYouEvent

/**
 * What changed between two looks at the fleet that a person should hear
 * about: a session that now needs them ([SessionRow.attentionReason], the
 * same rule the list's *Needs you* filter keeps) and did not before — or
 * needs them for a different reason. A session already waiting when
 * watching started, or still waiting for the same reason, is not news.
 *
 * [seen] is the reason each session had at the last look (null: needed
 * nothing); the answer carries the reasons as of [rows], for the next look.
 */
fun needsYouAlerts(
    seen: Map<Long, String?>,
    rows: List<SessionRow>,
    /** Who this person is and what is shared with them: a session they only watch is its owner's to answer (review w08-3). */
    access: MyAccess = MyAccess.UNKNOWN,
): Pair<List<NeedsYouAlert>, Map<Long, String?>> {
    val now = HashMap<Long, String?>(rows.size)
    val alerts = mutableListOf<NeedsYouAlert>()
    // Rows a fact about a host or a login newly blocks, by cause, and the
    // causes some row was already blocked by at the last look.
    val blocked = LinkedHashMap<String, MutableList<SessionRow>>()
    val known = HashSet<String>()
    for (row in rows) {
        // Someone else's to answer (watched, or seen through team reach): not
        // counted, not announced (reviews w08-3, w11-1).
        if (!access.waitsOnMe(row)) {
            now[row.id] = null
            continue
        }
        val reason = row.notifyReason
        val before = seen[row.id]
        if (reason != null && reason in FACT_REASONS) {
            // The row's own key rides along under the fact, so that when the
            // block lifts an old Failed or Done is not news again (review w11-3).
            now[row.id] = factKey(reason, ownKey(before))
            val cause = "$reason@${if (reason == "host_down") row.hostAlias else row.accountUuid ?: row.hostAlias}"
            if (factOf(before) == reason) known += cause else blocked.getOrPut(cause) { mutableListOf() } += row
            continue
        }
        val key = row.notifyKey
        now[row.id] = key
        if (reason == null) continue
        val own = ownKey(before)
        if (own == key) continue
        val sameReason = own != null && reasonOf(own) == reason
        // Same reason, and before knew no question: the dialog's question
        // arrived after the status (the hub reads the pane a moment after
        // the hook). The notification is updated, not announced again.
        // Same reason with a DIFFERENT question is a new question: a session
        // that asks again while still waiting is news, and used to be silent.
        val quiet = sameReason && questionOf(own) == null
        if (sameReason && questionOf(key) == null) continue
        alerts += NeedsYouAlert(
            row.id,
            row.displayName,
            "${reasonWords(reason)} · ${row.hostAlias}",
            row.supportingLine,
            reason,
            question = row.pendingInput?.question?.trim()?.takeIf { it.isNotEmpty() },
            quiet = quiet,
            retryable = reason == "failed" && inboxRowActions(row, canWrite = true, switchAvailable = false, limit = null).retryPrompt != null,
        )
    }
    // A host down or a login at its limit is one fact: said once, however
    // many sessions it blocks, and not again for a session that joins a
    // cause already said (review w11-2).
    for ((cause, hit) in blocked) {
        if (cause in known) continue
        val first = hit.minBy { it.id }
        val reason = cause.substringBefore('@')
        alerts += if (hit.size == 1) {
            NeedsYouAlert(first.id, first.displayName, "${reasonWords(reason)} · ${first.hostAlias}", first.supportingLine, reason)
        } else {
            NeedsYouAlert(
                first.id,
                first.hostAlias,
                "${hit.size} sessions are blocked",
                reason = reason,
                headline = when (reason) {
                    "host_down" -> "${first.hostAlias} is down"
                    "account_limit" -> "An account is at its limit"
                    else -> "A login on ${first.hostAlias} is signed out"
                },
            )
        }
    }
    return alerts to now
}

/**
 * The attention reasons that are a fact about a host or a login, not about
 * one session: every session there carries it at once, and it lifts for all
 * of them at once.
 */
internal val FACT_REASONS: Set<String> = setOf("host_down", "account_limit", "no_credentials")

/** What is remembered for a row a fact blocks: the fact, and the row's [own] key from before it. */
private fun factKey(reason: String, own: String?): String = "$reason|${own.orEmpty()}"

/** The fact a remembered [key] names, or null. A bare fact reason is one too (remembered before review w11-3). */
private fun factOf(key: String?): String? = key?.substringBefore('|')?.substringBefore('#')?.takeIf { it in FACT_REASONS }

/** The row's own key under a remembered [key]: what it was before a fact blocked it. */
private fun ownKey(key: String?): String? = when {
    key == null -> null
    '|' in key -> key.substringAfter('|').ifEmpty { null }
    factOf(key) != null -> null
    else -> key
}

/**
 * What [needsYouAlerts] remembers per session: the reason, and — when the
 * session shows a dialog — which dialog (`reason#<fingerprint hash>`), so a
 * second question while still `waiting` is told apart from the first.
 */
internal val SessionRow.notifyKey: String?
    get() {
        val reason = notifyReason ?: return null
        val dialog = pendingInput ?: return reason
        return "$reason#${dialog.fingerprint().hashCode().toUInt().toString(16)}"
    }

private fun reasonOf(key: String): String = key.substringBefore('#')
private fun questionOf(key: String?): String? = key?.substringAfter('#', "")?.ifEmpty { null }

/**
 * What a session is announced for: the hub's attention reason, else
 * [DONE_REASON] for one whose turn ended unread, as the desktop's
 * `isDoneUnread` has it (review w11-8), or whose task finished (`completed`,
 * the hub's word for a background agent done) — never for one running
 * outside fleet, never for Jev's proposal ([SessionRow.isProposed], contract
 * 15), and never for a routine's run that finished ([SessionRow.isRoutineRun]:
 * the routine's news, read in its runs; its failures and questions still
 * announce), and never for a lost, ghost or pending-kill row (`lifecycle`:
 * nobody can answer it, review w04-2), as the desktop does.
 */
internal val SessionRow.notifyReason: String?
    get() = attentionReason?.takeIf { needsAttention }
        ?: DONE_REASON.takeIf {
            attentionReason == null && isLive && kind != "external" && !isRoutineRun && !isProposed &&
                (claudeStatus == "completed" || (claudeStatus in DONE_UNREAD_STATUSES && isUnread))
        }

/** The resting statuses a turn's end leaves a session in: Done once it is unread. */
private val DONE_UNREAD_STATUSES: Set<String> = setOf("idle", "stopped")

/** The hub's attention reason as the notification says it — the app's one table, [reasonLabel]. */
fun reasonWords(reason: String): String = reasonLabel(reason)

/**
 * What a live fleet tells the notifier, as it changes. Each look is compared
 * to the last: a session that came to need a person is an alert, one that
 * stopped is resolved.
 *
 * The first connected look is compared to [remembered] — what the last run
 * saw, kept by the caller through [onSeen] — so a session that started
 * waiting while nothing watched is still news. With nothing remembered (the
 * first run ever), that look is the baseline: what already waited is on the
 * list, not news.
 */
fun needsYouEvents(
    fleet: FleetState,
    remembered: Map<Long, String?>? = null,
    onSeen: (Map<Long, String?>) -> Unit = {},
): Flow<NeedsYouEvent> = flow {
    var seen: Map<Long, String?>? = remembered
    var persisted: Map<Long, String?>? = null
    // Held until this connection has read who the person is: before that
    // every row reads as theirs, and a watched session would ring and then
    // be taken down, at every start of the watcher (reviews w11-5, w05-1).
    combine(fleet.sessions, fleet.status, fleet.access, fleet.accessSettled) { rows, status, access, settled ->
        Triple(rows, access, status is ConnectionStatus.Connected && settled)
    }
        .filter { (_, _, ready) -> ready }
        .collect { (rows, access, _) ->
            val before = seen
            val (alerts, after) = needsYouAlerts(before.orEmpty(), rows, access)
            seen = after
            // Once, then only when a reason (or its dialog) moved: `onSeen` persists the whole
            // map, and most session frames change none (review r16).
            if (after != persisted) {
                onSeen(after)
                persisted = after
            }
            if (before != null) {
                alerts.forEach { emit(it) }
                before.filter { (id, key) -> key != null && after[id] == null }.keys.forEach { emit(NeedsYouResolved(it)) }
            }
        }
}

/** Only the alerts — [needsYouEvents] without the resolutions or the memory. */
fun needsYouAlerts(fleet: FleetState): Flow<NeedsYouAlert> = flow {
    needsYouEvents(fleet).collect { if (it is NeedsYouAlert) emit(it) }
}

/** [needsYouEvents]'s memory as one line per session (`id=reason`, empty reason for none) — for a key-value store. */
fun encodeSeen(seen: Map<Long, String?>): List<String> = seen.map { (id, reason) -> "$id=${reason.orEmpty()}" }

fun decodeSeen(lines: List<String>): Map<Long, String?> = lines.mapNotNull { line ->
    val id = line.substringBefore('=').toLongOrNull() ?: return@mapNotNull null
    id to line.substringAfter('=', "").ifEmpty { null }
}.toMap()
