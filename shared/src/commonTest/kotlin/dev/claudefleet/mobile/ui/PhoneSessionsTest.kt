package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.ui.kit.StatusWord
import dev.claudefleet.mobile.ui.theme.StatusTone
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

    /** Redesign 2.7 / 4.10: Paused for an account at its limit; Done for a finished turn nobody read. */
    @Test
    fun a_limited_row_is_paused_and_an_unread_turn_is_done() {
        val limited = row(status = "idle", attention = Attention("account_limit"))
        assertEquals(StatusWord.PAUSED, phoneWord(limited))
        assertEquals(StatusTone.PAUSED, StatusTone.of(limited))
        assertEquals(StatusWord.PAUSED, StatusWord.of(StatusTone.PAUSED))
        assertEquals(StatusWord.PAUSED, phoneWord(row(status = "failed", attention = Attention("account_limit"))), "a turn that failed at the limit reads as the limit")
        assertNull(phoneLead(StatusWord.PAUSED, live = true), "its line already opens with Paused")
        val unread = row(status = "idle").copy(startedAt = 10, lastStopAt = 90, lastViewedAt = 50)
        assertEquals(StatusWord.DONE, phoneWord(unread))
        assertEquals(StatusWord.IDLE, phoneWord(unread.copy(lastViewedAt = 95)), "seen: Idle again")
    }

    @Test
    fun line_two_leads_with_waiting_for_you_and_says_what_it_waits_on() {
        assertEquals("Waiting for you", phoneLead(StatusWord.NEEDS_YOU, live = true))
        assertEquals("Working", phoneLead(StatusWord.WORKING, live = true))
        assertEquals("Was working", phoneLead(StatusWord.WORKING, live = false), "stale rows never pass for live")
        assertEquals("Was waiting for you", phoneLead(StatusWord.NEEDS_YOU, live = false))
        assertEquals("reconnecting… · as of 14:52", staleLine("reconnecting…", "14:52"))
        assertEquals("offline", staleLine("offline", null))
        val asked = row(status = "blocked", pending = PendingInput(kind = "permission", question = "Allow cargo fleet-test?"))
        assertEquals("Allow cargo fleet-test?", waitsOn(asked))
        assertEquals("Needs you", waitsOn(row(status = "blocked")))
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

    /**
     * Contract 15 (G1.6): Jev's "probably waiting" is listed apart, softly —
     * never in the Inbox, its count or the Needs you word — and the header
     * names it as "+N proposed".
     */
    @Test
    fun a_proposed_row_is_named_apart_and_never_counted() {
        val proposed = row(3, status = "idle", attention = Attention("probably_waiting", since = 60, state = "proposed"))
        val waiting = row(4, status = "blocked", attention = Attention("waiting", since = 50, state = "action_required"))
        val rows = listOf(proposed, waiting)
        assertEquals(listOf(4L), inboxRows(rows).map { it.id })
        assertEquals(listOf(3L), proposedRows(rows).map { it.id })
        assertEquals(StatusWord.IDLE, phoneWord(proposed), "shown in its own tone, not as Needs you")
        assertEquals("1 needs you · 0 running · +1 proposed", inboxSubtitle(1, 0, proposed = 1))
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
        assertEquals("Archived 2 sessions", bulkOutcomeTitle(BulkAction.Archive, 2))
        assertEquals("2 archived", bulkOutcomeLine(BulkAction.Archive, outcome.take(2)))
    }

    /** r09 B6: "All idle" picks the sessions whose agent waits at its prompt. */
    @Test
    fun all_idle_picks_the_idle_rows() {
        val rows = listOf(
            SessionRow(id = 1, claudeStatus = "idle"),
            SessionRow(id = 2, claudeStatus = "working"),
            SessionRow(id = 3, claudeStatus = "idle"),
            SessionRow(id = 4),
        )
        assertEquals(listOf(1L, 3L), idleIds(rows))
    }

    /** Step 4.10: a Blocked row says why in the desktop's words, naming the account. */
    @Test
    fun a_blocked_row_names_its_account_on_line_two() {
        val limited = row(status = "idle", attention = Attention("account_limit"))
        assertEquals("Paused · limit on tech.silvester", waitsOn(limited, "tech.silvester"))
        assertEquals("Paused · limit", waitsOn(limited))
        val signedOut = row(status = "idle", attention = Attention("no_credentials"))
        assertEquals("tech.silvester is signed out", waitsOn(signedOut, "tech.silvester"))
        assertEquals("Signed out", waitsOn(signedOut))
        assertEquals("hetzner-1 is down", waitsOn(row(host = "hetzner-1", attention = Attention("host_down"))))
        assertNull(blockedLine(row(attention = Attention("waiting")), "tech.silvester"))
    }

    /** Step 4.10: the account takes a chip left over, unless line two already names it. */
    @Test
    fun the_account_takes_a_spare_chip_and_never_repeats_line_two() {
        assertEquals(
            listOf("PR #476 ✓", "tech.silvester"),
            rowChips(row(pr = "https://github.com/o/r/pull/476", ci = "passing"), false, true, "tech.silvester").map { it.first },
        )
        assertEquals(
            listOf("PR #476 ✓", "mercury"),
            rowChips(row(pr = "https://github.com/o/r/pull/476", ci = "passing"), true, true, "tech.silvester").map { it.first },
        )
        val limited = row(attention = Attention("account_limit"))
        assertEquals(emptyList(), rowChips(limited, false, true, "tech.silvester"))
        assertEquals(listOf("tech.silvester"), rowChips(row(), false, true, "tech.silvester").map { it.first })
    }

    /** Step 4.10: with the account's usage reading, a paused row says which window and when it resets. */
    @Test
    fun a_paused_row_says_when_its_limit_resets() {
        val limited = row(status = "idle", attention = Attention("account_limit"))
        assertEquals(
            "Paused · limit on tech.silvester · resets in 2 h",
            waitsOn(limited, "tech.silvester", AccountLimit(weekly = false, resetsAt = 1_000 + 7_200), 1_000),
        )
        assertEquals(
            "Paused · weekly limit · resets in 3 d",
            waitsOn(limited, null, AccountLimit(weekly = true, resetsAt = 1_000 + 3 * 86_400), 1_000),
        )
        assertEquals("Paused · weekly limit on x", waitsOn(limited, "x", AccountLimit(weekly = true, resetsAt = null), 1_000))
    }
}
