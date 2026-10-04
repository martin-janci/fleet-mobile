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
}
