package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.SessionRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

/** One "a session needs you" notification: which session, and the two lines it shows. */
data class NeedsYouAlert(val sessionId: Long, val title: String, val text: String)

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
    val now = rows.associate { it.id to it.attentionReason }
    val alerts = rows.mapNotNull { row ->
        val reason = row.attentionReason ?: return@mapNotNull null
        if (seen[row.id] == reason) return@mapNotNull null
        NeedsYouAlert(row.id, row.displayName, "${reasonWords(reason)} · ${row.hostAlias}")
    }
    return alerts to now
}

/** The hub's attention reason as the notification says it. */
fun reasonWords(reason: String): String = when (reason) {
    "waiting" -> "Waiting for you"
    "stuck" -> "Stuck"
    "failed" -> "Failed"
    "lifecycle" -> "Needs a decision"
    else -> reason.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * The alerts a live fleet produces, as it changes. The first look once the
 * stream is connected is the baseline — what already needed a person then
 * is on the list, not news — and each later one is compared to the last.
 */
fun needsYouAlerts(fleet: FleetState): Flow<NeedsYouAlert> = flow {
    var seen: Map<Long, String?>? = null
    combine(fleet.sessions, fleet.status) { rows, status -> rows to status }
        .filter { (_, status) -> status is ConnectionStatus.Connected }
        .collect { (rows, _) ->
            val before = seen
            val (alerts, after) = needsYouAlerts(before.orEmpty(), rows)
            seen = after
            if (before != null) alerts.forEach { emit(it) }
        }
}
