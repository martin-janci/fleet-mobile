package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.WorkSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The chip's vocabulary (claude-fleet design §0.3.1), and the words a screen
 * reader says for it — the dash, ring, dot and strike-through carry nothing
 * to someone who cannot see them.
 */
class WorkChipTest {
    private val pay7 = WorkSummary(linkId = 1, itemId = 70, key = "PAY-7", title = "Refund", source = "manual", state = "confirmed")

    @Test
    fun a_suggestion_reads_as_a_guess_and_draws_a_question_mark() {
        val guess = pay7.copy(source = "prompt", state = "suggested", strength = "weak")
        assertEquals("PAY-7?", workChipText(guess, suggested = true))
        assertEquals("PAY-7 · Refund?", workChipText(guess, suggested = true, showTitle = true))
        assertEquals("Suggested work PAY-7, not confirmed", workChipDescription(guess, suggested = true))
    }

    @Test
    fun a_link_a_person_or_agent_made_is_plain() {
        for (source in listOf("manual", "agent", "started")) {
            val w = pay7.copy(source = source)
            assertFalse(isAutoLinked(w), source)
            assertEquals("PAY-7", workChipText(w, suggested = false))
            assertEquals("Work PAY-7", workChipDescription(w, suggested = false))
        }
    }

    @Test
    fun a_link_the_hub_made_by_itself_carries_the_ring_and_says_so() {
        val auto = pay7.copy(source = "branch", strength = "strong", rule = "R3")
        assertTrue(isAutoLinked(auto))
        assertEquals("Work PAY-7, linked automatically", workChipDescription(auto, suggested = false))
        assertFalse(isAutoLinked(auto.copy(strength = "explicit")), "explicit is a decision, whatever the source")
        assertFalse(isAutoLinked(auto.copy(source = "")), "an older hub that names no source: no claim either way")
    }

    @Test
    fun status_title_and_unavailability_are_said_not_only_drawn() {
        val w = pay7.copy(statusCategory = StatusCategory.InProgress, statusName = "In Review", unavailable = true)
        assertEquals("Work PAY-7, Refund, In Review, ticket unavailable", workChipDescription(w, suggested = false, showTitle = true))
        assertEquals(
            "Work PAY-7, in progress",
            workChipDescription(pay7.copy(statusCategory = StatusCategory.InProgress), suggested = false),
            "without the tracker's own name, the bucket in words",
        )
        assertEquals("Work PAY-7", workChipDescription(pay7.copy(statusCategory = StatusCategory.Unknown), suggested = false))
    }
}
