package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A `work:item` frame updates the ticket cache but not the session rows that
 * link the item; the overlay is what carries it to the chip and the heading.
 */
class WorkOverlayTest {
    private val stamped = WorkSummary(
        linkId = 1, itemId = 70, key = "PAY-7", title = "Refund", source = "branch",
        statusCategory = StatusCategory.Todo, statusName = "To Do",
    )
    private val row = SessionRow(id = 5, tmuxName = "t", work = stamped)

    @Test
    fun the_cache_is_the_newer_word_on_status_title_and_availability() {
        val ticket = Ticket(
            id = 70, key = "PAY-7", title = "Refund webhook",
            statusCategory = StatusCategory.Done, statusName = "Done", unavailableReason = "tracker_removed",
        )
        val fresh = row.withTicketsFrom(mapOf(70L to ticket)).work!!
        assertEquals("Refund webhook", fresh.title)
        assertEquals(StatusCategory.Done, fresh.statusCategory)
        assertEquals("Done", fresh.statusName)
        assertTrue(fresh.unavailable)
        assertEquals(stamped.linkId, fresh.linkId, "the link itself is the row's")
        assertEquals("branch", fresh.source)
    }

    @Test
    fun a_ticket_available_again_clears_the_strike() {
        val gone = row.copy(work = stamped.copy(unavailable = true))
        assertFalse(gone.withTicketsFrom(mapOf(70L to Ticket(id = 70, key = "PAY-7"))).work!!.unavailable)
    }

    @Test
    fun a_bare_key_or_an_unknown_item_is_left_as_stamped() {
        assertSame(row, row.withTicketsFrom(emptyMap()))
        assertSame(row, row.withTicketsFrom(mapOf(71L to Ticket(id = 71, key = "PAY-8"))))
        val bare = row.copy(work = stamped.copy(itemId = null))
        assertSame(bare, bare.withTicketsFrom(mapOf(70L to Ticket(id = 70))))
        // An empty ticket title keeps the row's.
        assertEquals("Refund", row.withTicketsFrom(mapOf(70L to Ticket(id = 70, title = ""))).work!!.title)
    }

    @Test
    fun the_suggestion_is_refreshed_too() {
        val guessed = SessionRow(id = 5, tmuxName = "t", workSuggested = stamped.copy(state = "suggested"))
        val fresh = guessed.withTicketsFrom(mapOf(70L to Ticket(id = 70, statusCategory = StatusCategory.InProgress)))
        assertEquals(StatusCategory.InProgress, fresh.workSuggested!!.statusCategory)
    }
}
