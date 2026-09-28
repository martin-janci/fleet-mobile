package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ResumeMode
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.TrackerRow
import dev.claudefleet.mobile.model.WorkSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The hub's reasons, in plain words first — and a reason this build does
 * not know is still shown, as the hub wrote it, and as text.
 */
class WorkReasonsTest {

    @Test
    fun an_unavailable_ticket_says_why_in_plain_words() {
        assertEquals("The tracker no longer answers for this ticket.", unavailableSentence(null))
        assertEquals("The tracker no longer answers for this ticket.", unavailableSentence("  "))
        assertEquals("Its tracker was removed from fleet.", unavailableSentence("tracker_removed"))
        assertEquals(
            "The tracker no longer finds this ticket, or fleet is no longer allowed to see it.",
            unavailableSentence("not_found_or_no_permission"),
        )
    }

    /** Text, never markup: a reason from a later hub is kept exactly as it came. */
    @Test
    fun a_reason_this_build_does_not_know_is_kept_verbatim() {
        assertEquals("The tracker no longer answers for this ticket (<b>archived</b>).", unavailableSentence("<b>archived</b>"))
        assertEquals(
            "Tracker Jira: it is not answering (maintenance), so statuses here may be out of date.",
            trackerSentence(TrackerRow(id = 1, name = "Jira", state = "maintenance")),
        )
    }

    @Test
    fun a_tracker_in_trouble_says_what_that_means_for_the_statuses() {
        fun say(state: String) = trackerSentence(TrackerRow(id = 1, provider = "jira", name = "Acme", state = state))

        assertNull(say("ok"))
        assertEquals("Tracker Acme: fleet cannot reach it, so statuses here may be out of date.", say("unreachable"))
        assertEquals("Tracker Acme: it is limiting fleet's requests, so statuses here may be out of date.", say("rate_limited"))
        assertEquals("Tracker Acme: it wants a person to sign in again, so statuses here may be out of date.", say("captcha"))
        assertEquals("Tracker Acme: it is not set up yet, so statuses here may be out of date.", say("unconfigured"))
        assertEquals(
            "Tracker jira: fleet's sign-in to it was refused, so statuses here may be out of date.",
            trackerSentence(TrackerRow(id = 1, provider = "jira", state = "auth_failed")),
            "no name: the provider",
        )
    }

    /** The cache's copy counts only when it is the same item as the link's. */
    @Test
    fun another_items_reason_is_not_this_ones() {
        val work = WorkSummary(linkId = 1, itemId = 70, key = "PAY-7", unavailable = true)
        val other = Ticket(id = 71, key = "PAY-8", unavailableReason = "tracker_removed")

        assertEquals(listOf("The tracker no longer answers for this ticket."), workTrouble(work, other, emptyList()))
        assertEquals(emptyList(), workTrouble(work.copy(unavailable = false), null, emptyList()))
    }

    @Test
    fun resume_says_why_not_only_when_the_plan_refuses_last() {
        fun plan(vararg modes: ResumeMode) = ResumePlan(key = "PAY-9", modes = modes.toList())

        assertNull(resumeWhyNot(null))
        assertNull(resumeWhyNot(plan(ResumeMode("last", ok = true))))
        assertNull(resumeWhyNot(plan(ResumeMode("last", ok = false))), "no reason given: nothing to say")
        assertNull(resumeWhyNot(plan(ResumeMode("fresh", ok = false, reason = "no host"))), "only last is offered here")
        assertEquals(
            "Can't resume the last conversation: session w still holds that conversation; restore or jump to it",
            resumeWhyNot(plan(ResumeMode("last", ok = false, reason = "session w still holds that conversation; restore or jump to it"))),
        )
    }
}
