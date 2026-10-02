package dev.claudefleet.mobile.model

import kotlinx.serialization.Serializable

/**
 * One of the Tickets sheet's lists — a hub `work tickets` view. [wire] is the
 * view name the hub takes; [label] is the section's heading.
 */
@Serializable
enum class TicketList(val wire: String, val label: String) {
    MINE("mine", "My work"),
    SPRINT("sprint", "Current sprint"),
    RECENT("recent", "Recent"),
    ;

    companion object {
        fun of(wire: String): TicketList? = entries.firstOrNull { it.wire == wire }
    }
}

/** Whether a session is on the ticket — the Work view's *Sessions* question, for tickets. */
@Serializable
enum class TicketSessionFilter(val label: String) {
    LIVE("Has a session"),
    NONE("No session"),
}

/**
 * How each section is ordered. A view, not a filter: it hides no ticket, so
 * it is outside [TicketFilters] and the count, like the Sessions list's Group.
 */
@Serializable
enum class TicketSort(val label: String) {
    /**
     * The order the hub listed them in — its cache is `updated_ext DESC`, so
     * newest tracker update first AT READ TIME.
     *
     * It used to be labelled "Tracker" and documented as "what the tracker's
     * own list shows", which is an order the hub has never had: it does not
     * ask the tracker for an order, it sorts its own cache.
     */
    TRACKER("As listed"),
    /** In progress, then to do, then done, then no status: what is moving first. */
    STATUS("Status"),
    /**
     * The tracker's last update, newest first — recomputed over the cache as
     * it is NOW.
     *
     * Which is the whole of the difference from [TRACKER]: the sheet's rows
     * are refreshed from the ticket cache (`current()`), so a ticket the sync
     * has touched since the listing moves under this one and stays put under
     * [TRACKER].
     */
    UPDATED("Updated"),
    ;

    /** The next sort a tap on the chip gives. */
    val next: TicketSort get() = entries[(ordinal + 1) % entries.size]
}

/**
 * Everything that narrows the Tickets sheet, in one value — the Sessions
 * list's [SessionFilters] for tickets. The search field's text is not in
 * here: it is the sheet's lookup too, and it is not remembered.
 *
 * Every field's default narrows nothing, so [TicketFilters] `()` is "every
 * ticket in every list".
 */
@Serializable
data class TicketFilters(
    /** The lists shown; empty means all three. */
    val lists: Set<TicketList> = emptySet(),
    /** Ticket status buckets, OR-ed with [statusNames]; both empty means any status. */
    val statuses: Set<WorkStatusFilter> = emptySet(),
    /** Tracker status names ("Code Review"), matched case-insensitively. */
    val statusNames: Set<String> = emptySet(),
    val org: Long? = null,
    val tracker: Long? = null,
    val session: TicketSessionFilter? = null,
) {
    /**
     * Whether anything narrows the lists.
     *
     * Copied from `SessionFilters` with none of its readers, so for a while
     * nothing read it and production asked the same question of the facet list
     * instead — two answers to one question, free to disagree. It is now the
     * one source: the filter page's Clear all and its button read it, and the
     * facet strip is a RENDERING of the same filters rather than their
     * definition.
     */
    val any: Boolean get() = this != TicketFilters()

    /**
     * Does [ticket] pass every filter but [lists] (which picks sections, not
     * rows)? [orgOf] is the ticket's org, [live] whether a session is on it.
     */
    fun matches(ticket: Ticket, orgOf: (Ticket) -> Long?, live: (Ticket) -> Boolean): Boolean {
        if (statuses.isNotEmpty() || statusNames.isNotEmpty()) {
            // A ticket with no status answers no status question — the
            // Sessions list's rule for its work filter too.
            val byBucket = ticket.statusCategory?.let { c -> statuses.any { it.category == c } } == true
            val name = ticket.statusName?.trim()
            val byName = name != null && statusNames.any { it.equals(name, ignoreCase = true) }
            if (!byBucket && !byName) return false
        }
        if (org != null && orgOf(ticket) != org) return false
        if (tracker != null && ticket.trackerId != tracker) return false
        when (session) {
            null -> Unit
            TicketSessionFilter.LIVE -> if (!live(ticket)) return false
            TicketSessionFilter.NONE -> if (live(ticket)) return false
        }
        return true
    }
}

/**
 * The search field as a filter: [query] narrows the lists to tickets whose
 * key or title holds it, case-insensitively. A pasted URL narrows nothing —
 * no key or title contains one, and hiding every ticket while a person
 * pastes a link to look up would read as "nothing here".
 */
fun ticketMatchesQuery(ticket: Ticket, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty() || isTicketUrl(q)) return true
    return ticket.key?.contains(q, ignoreCase = true) == true || ticket.title.contains(q, ignoreCase = true)
}

/** The search field holds a link, which only the hub's lookup can answer. */
fun isTicketUrl(query: String): Boolean = "://" in query

/**
 * Whether the field's text could BE a ticket the hub can look up: a URL, or a
 * tracker key — letters or digits, a `-`, then digits (`PAY-9`, `OM-110`), as
 * `work lookup` parses one.
 *
 * It gates the keyboard's Search action. The field is a live filter as well as
 * the sheet's lookup, and with `ImeAction.Search` unconditional the natural
 * key for dismissing the keyboard sent ordinary filter words to the hub as a
 * key lookup — a live tracker fetch for the word "login", answered with an
 * error banner over lists that were matching it perfectly well.
 */
fun isLookupText(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return false
    if (isTicketUrl(q)) return true
    val dash = q.lastIndexOf('-')
    if (dash <= 0 || dash == q.length - 1) return false
    return q.substring(0, dash).all { it.isLetterOrDigit() || it == '_' } &&
        q.substring(dash + 1).all { it.isDigit() }
}

/** [tickets] in [sort]'s order; stable, so equal tickets keep the hub's order. */
fun sortTickets(tickets: List<Ticket>, sort: TicketSort): List<Ticket> = when (sort) {
    TicketSort.TRACKER -> tickets
    TicketSort.STATUS -> tickets.sortedBy { statusRank(it.statusCategory) }
    TicketSort.UPDATED -> tickets.sortedByDescending { it.updatedExt ?: Long.MIN_VALUE }
}

/** Workflow order for sorting: what is moving, what is next, what is done, the rest. */
private fun statusRank(category: StatusCategory?): Int = when (category) {
    StatusCategory.InProgress -> 0
    StatusCategory.Todo -> 1
    StatusCategory.Done -> 2
    else -> 3
}

/**
 * The tracker status names [tickets] are in, one per name case-insensitively,
 * in workflow order and by name within it — [workStatusNames]'s rule, asked
 * of tickets rather than sessions.
 */
fun ticketStatusNames(tickets: List<Ticket>): List<String> =
    rankedStatusNames(tickets.asSequence().map { it.statusName to it.statusCategory })
