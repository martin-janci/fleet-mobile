package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Row parity for the New Sessions tab and the Inbox (redesign 14.3,
 * analysis-sessions.md §B): one status word per row instead of a dot and a
 * pill saying the same thing, what the row waits on on line two, the host
 * out of line two, and at most two chips.
 */
class PhoneSessionsTest {

    private fun row(
        id: Long = 1,
        status: String? = "working",
        stuck: String? = null,
        activity: String? = null,
        host: String = "mercury",
        pr: String? = null,
        ci: String? = null,
        work: WorkSummary? = null,
        pending: PendingInput? = null,
        attention: Attention? = null,
        last: Long? = 100,
    ) = SessionRow(
        id = id,
        tmuxName = "s$id",
        hostAlias = host,
        claudeStatus = status,
        stuckKind = stuck,
        currentActivity = activity,
        prUrl = pr,
        ciStatus = ci,
        work = work,
        pendingInput = pending,
        attention = attention,
        lastActivityAt = last,
    )

    @Test
    fun a_row_says_one_of_the_six_words() {
        assertEquals(StatusWord.NEEDS_YOU, phoneWord(row(status = "blocked")))
        assertEquals(StatusWord.FAILED, phoneWord(row(status = "failed")))
        assertEquals(StatusWord.FAILED, phoneWord(row(status = "idle", stuck = "trust_prompt")))
        assertEquals(StatusWord.WORKING, phoneWord(row(status = "working")))
        assertEquals(StatusWord.IDLE, phoneWord(row(status = "idle")))
        assertEquals(StatusWord.DONE, phoneWord(row(status = "completed")))
        assertNull(phoneWord(row(status = null)))
    }

    @Test
    fun line_two_leads_with_waiting_for_you_and_says_what_it_waits_on() {
        assertEquals("Waiting for you", phoneLead(StatusWord.NEEDS_YOU, live = true))
        assertEquals("Working", phoneLead(StatusWord.WORKING, live = true))
        assertEquals("Was working", phoneLead(StatusWord.WORKING, live = false), "stale rows never pass for live")
        val asked = row(status = "blocked", pending = PendingInput(kind = "permission", question = "Allow cargo fleet-test?"))
        assertEquals("Allow cargo fleet-test?", waitsOn(asked))
        assertEquals("Waiting for you", waitsOn(row(status = "blocked")))
        assertEquals("", waitsOn(row(status = "idle")))
    }

    @Test
    fun the_pr_and_its_ci_are_one_chip() {
        assertEquals("PR #476 ✓" to StatusWord.DONE, prChip(row(pr = "https://github.com/o/r/pull/476", ci = "passing")))
        assertEquals("PR #12 ✗" to StatusWord.FAILED, prChip(row(pr = "https://github.com/o/r/pull/12", ci = "failing")))
        assertEquals("CI ✓" to StatusWord.DONE, prChip(row(ci = "passing")))
        assertNull(prChip(row()))
    }

    @Test
    fun at_most_two_chips_and_the_host_only_where_no_heading_names_it() {
        val w = WorkSummary(key = "FLEET-142")
        val r = row(pr = "https://github.com/o/r/pull/476", ci = "passing", work = w)
        assertEquals(listOf("FLEET-142", "PR #476 ✓"), rowChips(r, showHost = false, showWork = true).map { it.first })
        assertEquals(listOf("PR #476 ✓", "mercury"), rowChips(r, showHost = true, showWork = true).map { it.first })
        assertEquals(listOf("FLEET-142", "mercury"), rowChips(row(work = w), showHost = true, showWork = true).map { it.first })
        assertEquals(emptyList(), rowChips(row(), showHost = false, showWork = true))
    }

    @Test
    fun the_state_view_bands_needs_you_first_and_keeps_order_inside_a_band() {
        val rows = listOf(
            row(1, status = "working"),
            row(2, status = "blocked"),
            row(3, status = "failed"),
            row(4, status = "blocked"),
            row(5, status = "idle"),
        )
        val bands = stateBands(rows)
        assertEquals(listOf(StatusWord.NEEDS_YOU, StatusWord.FAILED, StatusWord.WORKING, StatusWord.IDLE), bands.map { it.first })
        assertEquals(listOf(2L, 4L), bands.first().second.map { it.id })
    }

    @Test
    fun the_inbox_is_sorted_by_when_each_one_asked() {
        // Row 1 was active most recently but asked first; row 2 asked later.
        val rows = listOf(
            row(1, status = "blocked", attention = Attention("waiting", since = 50), last = 900),
            row(2, status = "blocked", attention = Attention("waiting", since = 400), last = 410),
        )
        assertEquals(listOf(1L, 2L), inboxRows(rows).map { it.id })
        assertEquals("1 needs you · 0 running", inboxSubtitle(1, 0))
        assertEquals("4 need you · 6 running", inboxSubtitle(4, 6))
    }

    @Test
    fun the_header_and_the_outcome_say_it_in_words() {
        assertEquals("22 on 5 hosts", sessionsOnHosts(22, 5))
        assertEquals("1 on 1 host", sessionsOnHosts(1, 1))
        val outcome = listOf(
            BulkOutcome(1, "a", ok = true),
            BulkOutcome(2, "b", ok = true),
            BulkOutcome(3, "c", ok = false, reason = "hetzner-1 did not answer"),
        )
        assertEquals("Sent to 3 sessions", bulkOutcomeTitle(BulkAction.Send("go"), 3))
        assertEquals("2 delivered, 1 not delivered", bulkOutcomeLine(BulkAction.Send("go"), outcome))
        assertEquals("Killed 1 session", bulkOutcomeTitle(BulkAction.Kill, 1))
        assertEquals("2 killed", bulkOutcomeLine(BulkAction.Kill, outcome.take(2)))
    }
}
