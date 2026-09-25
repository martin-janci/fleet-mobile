package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.WorkSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** A work group's heading, as a screen reader says it. */
class WorkHeaderTest {
    private val pay7 = WorkSummary(linkId = 1, itemId = 70, key = "PAY-7", title = "Refund", source = "manual")

    @Test
    fun the_heading_says_key_title_status_and_who_is_waiting() {
        assertEquals("Work PAY-7, Refund", workHeaderDescription(pay7, attention = 0))
        assertEquals(
            "Work PAY-7, Refund, In Review, ticket unavailable, 2 sessions need you",
            workHeaderDescription(pay7.copy(statusName = "In Review", unavailable = true), attention = 2),
        )
        assertEquals("Work PAY-7, Refund, 1 session needs you", workHeaderDescription(pay7, attention = 1))
        assertEquals("Work Refunds", workHeaderDescription(WorkSummary(title = "Refunds"), attention = 0), "a keyless item is its title")
    }
}
