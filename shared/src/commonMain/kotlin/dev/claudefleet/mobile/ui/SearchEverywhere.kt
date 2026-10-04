package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow

/**
 * What the Sessions search finds beyond the sessions themselves — the
 * desktop's ⌘K on the phone: the hosts and projects whose name holds every
 * word, and the query as a ticket to look up.
 */
data class SearchHits(
    val query: String = "",
    val hosts: List<HostRow> = emptyList(),
    val projects: List<ProjectRow> = emptyList(),
    /** Offer "Find ticket “query”" — tickets search by key, URL or words. */
    val ticket: Boolean = false,
) {
    val isEmpty: Boolean get() = hosts.isEmpty() && projects.isEmpty() && !ticket
}

/** At most this many hosts and projects each: the list is about sessions. */
private const val MAX_HITS = 5

fun searchEverywhere(query: String, hosts: List<HostRow>, projects: List<ProjectRow>, ticketsAvailable: Boolean): SearchHits {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return SearchHits()
    fun matches(text: String) = words.all { it in text.lowercase() }
    return SearchHits(
        query = query.trim(),
        hosts = hosts.filter { !it.hidden && matches(it.alias) }.take(MAX_HITS),
        projects = projects.filter { matches(it.label) || matches("${it.owner}/${it.repo}") }
            .sortedByDescending { it.lastSessionAt ?: 0 }
            .take(MAX_HITS),
        ticket = ticketsAvailable,
    )
}
