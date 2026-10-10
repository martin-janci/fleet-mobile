package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchEverywhereTest {

    private val hosts = listOf(HostRow("pine-1"), HostRow("oak"), HostRow("pine-hidden", hidden = true))
    private val projects = listOf(
        ProjectRow(id = 1, owner = "acme", repo = "billing", lastSessionAt = 10),
        ProjectRow(id = 2, owner = "acme", repo = "billing-ui", lastSessionAt = 20),
        ProjectRow(id = 3, owner = "acme", repo = "docs"),
    )

    @Test
    fun hosts_and_projects_that_hold_every_word() {
        val hits = searchEverywhere("pine", hosts, projects, ticketsAvailable = true)
        assertEquals(listOf("pine-1"), hits.hosts.map { it.alias }, "a hidden host is not offered")

        val billing = searchEverywhere("acme bill", hosts, projects, ticketsAvailable = false)
        // The most recently used first.
        assertEquals(listOf(2L, 1L), billing.projects.map { it.id })
        assertFalse(billing.ticket)
    }

    @Test
    fun a_blank_query_finds_nothing() {
        assertTrue(searchEverywhere("  ", hosts, projects, ticketsAvailable = true).isEmpty)
    }

    @Test
    fun the_query_is_offered_as_a_ticket_where_tickets_are_served() {
        val hits = searchEverywhere(" PAY-12 ", hosts, projects, ticketsAvailable = true)
        assertTrue(hits.ticket)
        assertEquals("PAY-12", hits.query)
    }

    @Test
    fun a_project_holding_a_matching_file_name_says_which_file() {
        val names = ProjectFileNames()
        names.record(3, listOf("shared/", "shared/ui/HostsScreen.kt", "shared/ui/HostsViewModel.kt", "README.md"))
        val hits = searchEverywhere("hosts", hosts, projects, ticketsAvailable = false, projectFiles = names.byProject.value)
        assertEquals(listOf(3L), hits.projects.map { it.id })
        assertEquals("matches HostsScreen.kt", hits.projectLine(projects[2], "New session in this project"))
    }

    @Test
    fun a_project_found_by_its_name_comes_first_and_says_nothing_more() {
        val files = mapOf(1L to listOf("src/Docs.kt"), 3L to listOf("guide/docs-index.md"))
        val hits = searchEverywhere("docs", hosts, projects, ticketsAvailable = false, projectFiles = files)
        assertEquals(listOf(3L, 1L), hits.projects.map { it.id })
        assertEquals("New session in this project", hits.projectLine(projects[2], "New session in this project"))
        assertEquals("matches Docs.kt", hits.projectLine(projects[0], "New session in this project"))
    }

    @Test
    fun folder_names_and_whole_paths_do_not_count_as_a_file_match() {
        assertEquals(null, fileNameMatch(listOf("hosts"), listOf("hosts/", "hosts/Main.kt")))
        assertEquals("Hosts.kt", fileNameMatch(listOf("hosts"), listOf("a/HostsScreen.kt", "b/Hosts.kt")), "the shortest name wins")
    }
}
