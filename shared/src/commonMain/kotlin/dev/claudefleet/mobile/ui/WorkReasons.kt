package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.TRACKER_REMOVED
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WorkSummary

/*
 * The hub's reasons, said where a person can read them: why a ticket is
 * struck through, why its tracker's statuses may be stale, why Resume is not
 * offered. The hub has said each of these all along; the screens used to
 * draw only the outcome — a line through the key, a button that was not
 * there — and never the why.
 *
 * Everything returned here is drawn as plain text. The reasons are the hub's
 * vocabulary and sentences, and a reason this build does not know is still
 * shown, as the hub wrote it, rather than dropped.
 */

/** The hub's `unavailable_reason` for a ticket its tracker stopped finding. */
internal const val NOT_FOUND_OR_NO_PERMISSION = "not_found_or_no_permission"

/**
 * What to say about [work] and its tracker: whether the tracker still
 * answers for the ticket, and why not ([Ticket.unavailableReason]); and the
 * tracker's own trouble, when it is in any ([TrackerRow.state]). [ticket] is
 * the cache's copy of the item, which is what knows the reason and the
 * tracker; without it only the row's own `unavailable` flag is known.
 */
internal fun workTrouble(work: WorkSummary, ticket: Ticket?, trackers: List<TrackerRow>): List<String> {
    val cached = ticket?.takeIf { it.id == work.itemId }
    return ticketTrouble(unavailable = work.unavailable || cached?.unavailable == true, cached, trackers)
}

/** [workTrouble] for a ticket the Tickets sheet shows. */
internal fun ticketTrouble(ticket: Ticket, trackers: List<TrackerRow>): List<String> =
    ticketTrouble(ticket.unavailable, ticket, trackers)

private fun ticketTrouble(unavailable: Boolean, ticket: Ticket?, trackers: List<TrackerRow>): List<String> = buildList {
    if (unavailable) add(unavailableSentence(ticket?.unavailableReason))
    val tracker = ticket?.trackerId?.let { id -> trackers.firstOrNull { it.id == id } }
    // A removed tracker is already the reason above; its row is gone anyway.
    if (tracker != null && ticket.unavailableReason != TRACKER_REMOVED) trackerSentence(tracker)?.let(::add)
}

/** Why the tracker no longer answers for a ticket, in plain words first. */
internal fun unavailableSentence(reason: String?): String = when (val r = reason?.trim()?.takeIf { it.isNotEmpty() }) {
    null -> "The tracker no longer answers for this ticket."
    TRACKER_REMOVED -> "Its tracker was removed from fleet."
    NOT_FOUND_OR_NO_PERMISSION -> "The tracker no longer finds this ticket, or fleet is no longer allowed to see it."
    else -> "The tracker no longer answers for this ticket ($r)."
}

/**
 * A tracker that is not `ok`, said as what it means for the statuses on
 * screen; null for one that is. Any state other than `ok` is trouble — the
 * hub's own rule for a state it adds later.
 */
internal fun trackerSentence(tracker: TrackerRow): String? {
    val state = tracker.state.trim()
    if (state == "ok") return null
    val what = when (state) {
        "auth_failed" -> "fleet's sign-in to it was refused"
        "rate_limited" -> "it is limiting fleet's requests"
        "unreachable" -> "fleet cannot reach it"
        "captcha" -> "it wants a person to sign in again"
        "unconfigured" -> "it is not set up yet"
        "" -> "it is not answering"
        else -> "it is not answering ($state)"
    }
    val name = tracker.name.trim().ifEmpty { tracker.provider.trim() }.ifEmpty { "Tracker ${tracker.id}" }
    return "Tracker $name: $what, so statuses here may be out of date."
}

/**
 * Why Resume is not offered, when the hub's plan says so: the `last` mode's
 * `reason` ("its transcripts were purged", "the conversation's transcript is
 * on pine; …"). Null when the plan allows it, or gives no reason.
 */
internal fun resumeWhyNot(plan: ResumePlan?): String? {
    val last = plan?.modes?.firstOrNull { it.mode == "last" } ?: return null
    if (last.ok) return null
    return last.reason?.trim()?.takeIf { it.isNotEmpty() }?.let { "Can't resume the last conversation: $it" }
}
