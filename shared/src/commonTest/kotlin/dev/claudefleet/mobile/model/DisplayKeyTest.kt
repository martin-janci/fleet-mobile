package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.components.workChipText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Fleet's own ticket keys (claude-fleet migration 168): a GitHub issue or an
 * Asana task carries `display_key` (`CF-12`) beside its tracker key, and the
 * phone shows it — while grouping and every action stay on `key`.
 */
class DisplayKeyTest {
    @Test
    fun a_session_shows_the_ticket_key_but_groups_by_its_own_key() {
        val w = json.decodeFromString(
            WorkSummary.serializer(),
            """{"link_id":1,"item_id":7,"key":"asana:1209876543210987","display_key":"CB-4","title":"Refunds"}""",
        )
        assertEquals("CB-4", w.displayKey)
        assertEquals("CB-4", w.shownLabel)
        assertEquals("asana:1209876543210987", w.label)
        assertEquals("ASANA:1209876543210987", w.groupKey)
        assertEquals("CB-4 · Refunds", workChipText(w, suggested = false, showTitle = true))
        assertEquals("CB-4?", workChipText(w, suggested = true))
    }

    @Test
    fun an_older_hub_without_it_reads_as_before() {
        val w = json.decodeFromString(WorkSummary.serializer(), """{"link_id":1,"key":"ABC-12"}""")
        assertNull(w.displayKey)
        assertEquals("ABC-12", w.shownLabel)
        val t = json.decodeFromString(WorkTask.serializer(), """{"task_id":"item:1","key":"acme/web#7"}""")
        assertEquals("acme/web#7", t.label)
    }

    @Test
    fun tasks_tickets_and_review_rows_lead_with_the_ticket_key() {
        val t = json.decodeFromString(
            WorkTask.serializer(),
            """{"task_id":"item:1","item_id":1,"key":"acme/web#7","display_key":"WEB-3","title":"Fix"}""",
        )
        assertEquals("WEB-3", t.label)
        assertEquals("acme/web#7", t.key, "actions still send the tracker key")
        val k = json.decodeFromString(Ticket.serializer(), """{"id":1,"key":"asana:1","display_key":"CB-1"}""")
        assertEquals("CB-1", k.label)
        assertEquals("CB-1", LinkTask(key = "asana:1", displayKey = "CB-1").label)
        assertEquals("CB-1", ReviewTask(key = "asana:1", displayKey = "CB-1").label)
        assertEquals("CB-1", ReviewAlternative(key = "asana:1", displayKey = "CB-1").label)
    }

    @Test
    fun a_ticket_frame_keeps_the_ticket_key_on_the_session() {
        val w = WorkSummary(itemId = 7, key = "asana:1")
        val t = Ticket(id = 7, key = "asana:1", displayKey = "CB-1", title = "Refunds")
        assertEquals("CB-1", w.refreshedBy(t).shownLabel)
        assertEquals("CB-1", w.copy(displayKey = "CB-1").refreshedBy(t.copy(displayKey = null)).shownLabel)
    }
}
