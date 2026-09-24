package dev.claudefleet.mobile.ui.components

import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.theme.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun session(work: WorkSummary? = null, suggested: WorkSummary? = null) =
    SessionRow(id = 1, tmuxName = "t", work = work, workSuggested = suggested)

/** The desktop's chip vocabulary (claude-fleet design §0.3.1), on the phone. */
class WorkChipTest {

    @Test
    fun a_link_a_person_or_agent_made_is_solid() {
        for (source in listOf("manual", "agent")) {
            val look = workChipLook(session(work = WorkSummary(key = "PAY-7", source = source, state = "confirmed")))!!
            assertEquals(WorkChipLook("PAY-7", WorkChipStyle.Solid, struck = false), look)
        }
        val explicit = workChipLook(session(work = WorkSummary(key = "PAY-7", source = "prompt", strength = "explicit")))!!
        assertEquals(WorkChipStyle.Solid, explicit.style)
    }

    @Test
    fun a_link_the_hub_made_by_itself_carries_the_ring() {
        val look = workChipLook(session(work = WorkSummary(key = "PAY-7", source = "branch", strength = "strong", rule = "R3")))!!
        assertEquals(WorkChipStyle.Auto, look.style)
        assertEquals("work PAY-7, linked automatically", look.spoken)
    }

    @Test
    fun a_suggestion_is_dashed_with_a_question_mark_and_only_without_a_link() {
        val guess = WorkSummary(linkId = 12, key = "PAY-9", state = "suggested")
        val look = workChipLook(session(suggested = guess))!!
        assertEquals(WorkChipLook("PAY-9?", WorkChipStyle.Suggested, struck = false), look)
        assertEquals("work PAY-9, suggested", look.spoken)

        val both = workChipLook(session(work = WorkSummary(key = "PAY-7", source = "manual"), suggested = guess))!!
        assertEquals("PAY-7", both.text, "the confirmed link wins the row")
    }

    @Test
    fun an_unavailable_ticket_is_struck() {
        val look = workChipLook(session(work = WorkSummary(key = "PAY-7", source = "manual", unavailable = true)))!!
        assertEquals(true, look.struck)
        assertEquals("work PAY-7, unavailable", look.spoken)
    }

    @Test
    fun no_work_no_chip() {
        assertNull(workChipLook(session()))
        assertNull(workChipLook(session(work = WorkSummary(key = " ", title = ""))), "nothing to say")
    }

    @Test
    fun a_keyless_item_is_named_by_its_title() {
        assertEquals("Refunds", workChipLook(session(work = WorkSummary(title = "Refunds", source = "manual")))!!.text)
    }

    @Test
    fun a_ticket_status_has_a_tone() {
        assertEquals(StatusTone.IDLE, StatusCategory.Todo.tone())
        assertEquals(StatusTone.WORKING, StatusCategory.InProgress.tone())
        assertEquals(StatusTone.COMPLETED, StatusCategory.Done.tone())
        assertEquals(StatusTone.UNKNOWN, StatusCategory.Unknown.tone())
        assertEquals(StatusTone.UNKNOWN, (null as StatusCategory?).tone())
    }
}
