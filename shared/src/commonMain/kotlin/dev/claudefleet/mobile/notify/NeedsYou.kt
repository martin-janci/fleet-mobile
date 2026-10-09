package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.reasonLabel
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
fun needsYouAlerts(seen: Map<Long, String?>, rows: List<SessionRow>): Pair<List<NeedsYouAlert>, Map<Long, String?>> {
    val now = rows.associate { it.id to it.notifyReason }
    val alerts = rows.mapNotNull { row ->
        val reason = row.notifyReason ?: return@mapNotNull null
        if (seen[row.id] == reason) return@mapNotNull null
        NeedsYouAlert(
            row.id,
            row.displayName,
            "${reasonWords(reason)} · ${row.hostAlias}",
            row.supportingLine,
            reason,
            question = row.pendingInput?.question?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
    return alerts to now
}

/**
 * What a session is announced for: the hub's attention reason, else
 * [DONE_REASON] for one that finished its task (`completed`, the hub's word
 * for a background agent done) — never for one running outside fleet.
 */
internal val SessionRow.notifyReason: String?
    get() = attentionReason ?: DONE_REASON.takeIf { claudeStatus == "completed" && kind != "external" }

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
    combine(fleet.sessions, fleet.status) { rows, status -> rows to status }
        .filter { (_, status) -> status is ConnectionStatus.Connected }
        .collect { (rows, _) ->
            val before = seen
            val (alerts, after) = needsYouAlerts(before.orEmpty(), rows)
            seen = after
            // Once, then only when a reason moved: `onSeen` persists the whole
            // map, and most session frames change none (review r16).
            if (after != persisted) {
                onSeen(after)
                persisted = after
            }
            if (before != null) {
                alerts.forEach { emit(it) }
                before.filter { (id, reason) -> reason != null && after[id] == null }.keys.forEach { emit(NeedsYouResolved(it)) }
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
