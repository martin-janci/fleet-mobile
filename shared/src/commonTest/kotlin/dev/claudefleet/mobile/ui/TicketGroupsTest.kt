package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Ticket
import kotlin.test.Test
import kotlin.test.assertEquals

/** The New layout's tickets grouped by organisation and tracker (MobileTidyTickets, r09 B15). */
class TicketGroupsTest {
    private val orgs = mapOf(1L to "Personal", 2L to "Acme", 3L to "Personal")
    private val trackers = mapOf(1L to "GitHub issues", 2L to "Linear", 3L to "GitHub issues")
    private fun groups(vararg ids: Long) =
        ticketGroups(ids.map { Ticket(id = it) }, { orgs[it.id] }, { trackers[it.id] })

    @Test
    fun groups_keep_the_order_each_first_appears_in() {
        val g = groups(1, 2, 3)
        assertEquals(listOf("Personal · GitHub issues", "Acme · Linear"), g.map { it.heading })
        assertEquals(listOf(1L, 3L), g[0].tickets.map { it.id })
    }

    @Test
    fun one_group_draws_no_heading() {
        val g = groups(1, 3)
        assertEquals(listOf<String?>(null), g.map { it.heading })
        assertEquals(listOf(1L, 3L), g.single().tickets.map { it.id })
    }

    @Test
    fun a_ticket_with_neither_goes_under_other() {
        val g = groups(1, 9)
        assertEquals(listOf("Personal · GitHub issues", "Other"), g.map { it.heading })
    }
}
