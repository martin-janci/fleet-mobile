package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the Sessions search finds beyond the sessions themselves — the
 * desktop's ⌘K on the phone: the hosts and projects whose name holds every
 * word, the projects holding a file whose name does, and the query as a
 * ticket to look up.
 */
data class SearchHits(
    val query: String = "",
    val hosts: List<HostRow> = emptyList(),
    val projects: List<ProjectRow> = emptyList(),
    /**
     * The file that brought a project in, by project id — the row's "matches
     * HostsScreen.kt" — for a project found by a file name and not its own.
     */
    val fileMatches: Map<Long, String> = emptyMap(),
    /** Offer "Find ticket “query”" — tickets search by key, URL or words. */
    val ticket: Boolean = false,
) {
    val isEmpty: Boolean get() = hosts.isEmpty() && projects.isEmpty() && !ticket
}

/** A project hit's second line: the file that matched, else [otherwise]. */
fun SearchHits.projectLine(project: ProjectRow, otherwise: String): String =
    fileMatches[project.id]?.let { "matches $it" } ?: otherwise

/**
 * The file names of each project the phone has read this run, by project id:
 * what a session's Files tab listed (`repo_tree`). The hub has no tool that
 * searches a project's files by name or content, and listing every project's
 * tree to answer a keystroke would cost a remote `git ls-files` per project,
 * so Search everywhere matches the names already here and no others. Per hub:
 * it lives on that hub's repository and goes with it.
 */
class ProjectFileNames {
    private val names = MutableStateFlow<Map<Long, List<String>>>(emptyMap())

    /** Each project's file paths (folders left out), as last listed. */
    val byProject: StateFlow<Map<Long, List<String>>> = names.asStateFlow()

    /** Keep [entries] — a `repo_tree` listing — as [projectId]'s files, in place of any older listing. */
    fun record(projectId: Long, entries: List<String>) {
        val files = entries.filter { !it.endsWith('/') }
        names.update { it + (projectId to files) }
    }
}

/** At most this many hosts and projects each: the list is about sessions. */
private const val MAX_HITS = 5

/**
 * The first file name (the last part of a path) in [paths] holding every one
 * of [words], the shortest when several do — the closest to what was typed.
 * Null when none does.
 */
internal fun fileNameMatch(words: List<String>, paths: List<String>): String? {
    if (words.isEmpty()) return null
    var best: String? = null
    for (path in paths) {
        val name = path.substringAfterLast('/')
        val lower = name.lowercase()
        if (words.all { it in lower } && (best == null || name.length < best.length)) best = name
    }
    return best
}

fun searchEverywhere(
    query: String,
    hosts: List<HostRow>,
    projects: List<ProjectRow>,
    ticketsAvailable: Boolean,
    /** [ProjectFileNames.byProject]: the file names the phone holds, by project id. */
    projectFiles: Map<Long, List<String>> = emptyMap(),
): SearchHits {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return SearchHits()
    fun matches(text: String) = words.all { it in text.lowercase() }
    val byName = projects.filter { matches(it.label) || matches("${it.owner}/${it.repo}") }
        .sortedByDescending { it.lastSessionAt ?: 0 }
    // A project found by its name says nothing more; one found by a file
    // names that file, and comes after the projects found by name.
    val byFile = projects.filter { it !in byName }
        .mapNotNull { p -> projectFiles[p.id]?.let { fileNameMatch(words, it) }?.let { p to it } }
        .sortedByDescending { it.first.lastSessionAt ?: 0 }
    val shown = (byName + byFile.map { it.first }).take(MAX_HITS)
    return SearchHits(
        query = query.trim(),
        hosts = hosts.filter { !it.hidden && matches(it.alias) }.take(MAX_HITS),
        projects = shown,
        fileMatches = byFile.filter { it.first in shown }.associate { it.first.id to it.second },
        ticket = ticketsAvailable,
    )
}
