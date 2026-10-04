package dev.claudefleet.mobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.ui.components.highlightMatches
import kotlin.test.Test
import kotlin.test.assertEquals

class FindAndTicketWordsTest {

    @Test
    fun every_match_is_marked_whatever_its_case() {
        val marked = highlightMatches(AnnotatedString("Deploy, then deploy again"), "DEPLOY", Color.Yellow)
        assertEquals(listOf(0 until 6, 13 until 19), marked.spanStyles.map { it.start until it.end })
    }

    @Test
    fun no_query_or_no_match_marks_nothing() {
        val text = AnnotatedString("nothing here")
        assertEquals(text, highlightMatches(text, "  ", Color.Yellow))
        assertEquals(0, highlightMatches(text, "absent", Color.Yellow).spanStyles.size)
    }

    @Test
    fun a_ticket_row_says_its_status_and_that_someone_is_on_it() {
        val t = Ticket(id = 1, key = "API-7", title = "Fix login", statusName = "In Progress", liveSessionIds = listOf(9))
        assertEquals("In Progress · live · Acme", ticketRowLine(t, "Acme"))
        assertEquals("", ticketRowLine(t.copy(statusName = null, liveSessionIds = emptyList()), null))
    }
}
