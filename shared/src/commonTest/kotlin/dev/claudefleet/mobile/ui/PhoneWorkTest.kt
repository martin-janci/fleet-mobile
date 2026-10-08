package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.PastWorkSummary
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TaskCounts
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.WorkTaskLink
import dev.claudefleet.mobile.ui.kit.StatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The New bar's Work (redesign 14.9, MobileWork): a task row says what its
 * sessions are doing in the six status words, amber only for Needs you and
 * red only for Failed; past sessions say how they ended; summaries are marked
 * Drafted with their source; the bulk action never counts a link across
 * organisations.
 */
class PhoneWorkTest {

    private fun row(id: Long, status: String?, name: String = "s$id") =
        SessionRow(id = id, tmuxName = name, hostAlias = "mercury", claudeStatus = status)

    private fun link(
        id: Long,
        state: LinkState = LinkState.Active,
        session: Long? = id,
        status: String? = null,
        needsYou: Boolean = false,
        endReason: String? = null,
        pr: String? = null,
    ) = WorkTaskLink(linkId = id, state = state, sessionId = session, claudeStatus = status, needsYou = needsYou, endReason = endReason, prUrl = pr)

    private fun task(vararg links: WorkTaskLink, status: String? = "In Progress", counts: TaskCounts? = null, needsYou: Boolean = false) = WorkTask(
        taskId = "item:1",
        key = "FLEET-142",
        title = "Hosts screen: last ping",
        statusName = status,
        sessions = links.toList(),
        needsYou = needsYou,
        counts = counts ?: TaskCounts(
            active = links.count { it.state == LinkState.Active },
            ended = links.count { it.state == LinkState.Ended },
            suggested = links.count { it.state == LinkState.Suggested },
        ),
    )

    @Test
    fun a_live_session_reads_its_own_row_and_falls_back_to_the_link() {
        val rows = mapOf(1L to row(1, "blocked"), 2L to row(2, "working"))
        assertEquals(StatusWord.NEEDS_YOU, linkWord(link(1), rows[1]))
        assertEquals(StatusWord.WORKING, linkWord(link(2), rows[2]))
        // No row on the phone: the link's own status and needs_you.
        assertEquals(StatusWord.IDLE, linkWord(link(3, status = "idle"), null))
        assertEquals(StatusWord.NEEDS_YOU, linkWord(link(4, status = "idle", needsYou = true), null))
    }

    @Test
    fun an_ended_session_says_how_it_ended() {
        assertEquals(StatusWord.DONE, linkWord(link(1, LinkState.Ended, session = null), null))
        assertEquals(StatusWord.FAILED, linkWord(link(2, LinkState.Ended, session = null, endReason = "failed"), null))
        assertNull(linkWord(link(3, LinkState.Ended, session = null, endReason = "killed"), null))
        assertEquals("Ended · killed", pastLead(link(3, LinkState.Ended, session = null, endReason = "killed")))
        assertEquals("Done", pastLead(link(1, LinkState.Ended, session = null)))
        assertNull(linkWord(link(4, LinkState.Rejected), null))
    }

    @Test
    fun a_task_row_leads_with_its_sessions_words_most_urgent_first() {
        val rows = mapOf(1L to row(1, "working"), 2L to row(2, "blocked"))
        val line = taskLine(task(link(1), link(2)), rows::get)
        assertEquals(StatusWord.NEEDS_YOU, line.word, "the dot takes the most urgent word")
        assertEquals("1 needs you · 1 working", line.lead)
        assertEquals("In Progress", line.line)
    }

    @Test
    fun failed_is_red_and_beats_working_but_not_needs_you() {
        val rows = mapOf(1L to row(1, "failed"), 2L to row(2, "working"))
        val line = taskLine(task(link(1), link(2)), rows::get)
        assertEquals(StatusWord.FAILED, line.word)
        // A failed row also needs a person; it still reads Failed, never Needs you.
        assertEquals("1 failed · 1 working", line.lead)
    }

    @Test
    fun a_task_with_no_live_session_says_what_it_last_had() {
        val none = taskLine(task(status = "To Do"), { null })
        assertNull(none.word)
        assertNull(none.lead)
        assertEquals("No session yet · To Do", none.line)

        val past = taskLine(task(link(9, LinkState.Ended, session = null, pr = "https://github.com/a/b/pull/118"), status = null), { null })
        assertEquals("PR #118 · 1 past session", past.line)
    }

    @Test
    fun a_blocked_task_reads_needs_you_with_what_it_waits_on() {
        // Redesign 6.7: Blocked shows as Needs you, its reason on the line.
        val labels = mapOf("item:7" to "FLEET-12")
        val idle = task(status = "To Do").copy(blocked = true, blockedBy = listOf("item:7"))
        val line = taskLine(idle, { null }, labels::get)
        assertEquals(StatusWord.NEEDS_YOU, line.word)
        assertEquals("Blocked on FLEET-12", line.lead)
        // What it last had is still said after the reason.
        assertEquals("No session yet · To Do", line.line)

        // A live session's own word still leads; the reason follows it.
        val rows = mapOf(1L to row(1, "working"))
        val working = task(link(1)).copy(blocked = true, blockedBy = listOf("item:7", "item:8"))
        val busy = taskLine(working, rows::get, labels::get)
        assertEquals(StatusWord.WORKING, busy.word)
        assertEquals("1 working", busy.lead)
        assertEquals("Blocked on FLEET-12 and 1 more · In Progress", busy.line)
    }

    @Test
    fun a_blocker_the_phone_has_not_loaded_is_counted_never_named() {
        fun blocked(vararg by: String) = task().copy(blocked = true, blockedBy = by.toList())
        assertEquals("Blocked on another task", blockedLine(blocked("item:9"), { null }))
        assertEquals("Blocked on 2 tasks", blockedLine(blocked("item:9", "item:10"), { null }))
        // The hub leaves out blockers this token may not see.
        assertEquals("Blocked", blockedLine(blocked(), { null }))
        // An older hub never sends `blocked`: nothing is said.
        assertNull(blockedLine(task(), { "FLEET-12" }))
        assertNull(taskLine(task(status = "To Do"), { null }).word)
    }

    @Test
    fun counts_from_the_hub_stand_in_for_sessions_not_on_the_page() {
        val line = taskLine(task(counts = TaskCounts(active = 2)), { null })
        assertEquals("2 active", line.lead)
        assertNull(line.word)
    }

    @Test
    fun the_subtitle_counts_sessions_that_need_you_once() {
        val rows = mapOf(1L to row(1, "blocked"), 2L to row(2, "working"))
        // The same session under two tasks is one session that needs you.
        val tasks = listOf(task(link(1), link(2)), task(link(1).copy(linkId = 11)))
        assertEquals(1, sessionsNeedingYou(tasks, rows::get))
        assertEquals("7 tasks · 3 sessions need you", myWorkSubtitle(7, 3))
        assertEquals("1 task · 1 session needs you", myWorkSubtitle(1, 1))
        assertEquals("0 tasks", myWorkSubtitle(0, 0))
    }

    @Test
    fun rows_put_the_key_first_and_groups_say_where_they_came_from() {
        assertEquals("FLEET-142 Hosts screen: last ping", taskTitle(task()))
        assertEquals("Local note", taskTitle(WorkTask(taskId = "item:3", title = "Local note")))
        assertEquals("by rule", groupChipWords(GroupSource.Rule))
        assertNull(groupChipWords(GroupSource.None))
    }

    @Test
    fun past_sessions_fold_under_one_line_with_their_pr() {
        val past = listOf(
            link(5, LinkState.Ended, session = null, pr = "https://github.com/a/b/pull/101"),
            link(6, LinkState.Ended, session = null),
        )
        assertEquals("2 past sessions · PR #101", pastFoldLine(past))
    }

    @Test
    fun a_summary_is_marked_drafted_with_its_source() {
        assertEquals(
            "Drafted by claude-haiku from this session's transcript",
            draftedLine(PastWorkSummary(key = "FLEET-142", linkId = 5, model = "claude-haiku", summary = "x")),
        )
        // Never an empty source: the old dialog read " · claude-haiku".
        assertEquals("Drafted from this session's transcript, cut short", draftedLine(PastWorkSummary(summary = "x", truncated = true)))
    }

    @Test
    fun the_bulk_action_names_only_ordinary_suggestions() {
        assertEquals("Link the 1 ordinary suggestion", linkAllLabel(1))
        assertEquals("Link the 3 ordinary suggestions", linkAllLabel(3))
    }

    /** 14.15: a ticket in the Tickets sheet says what its sessions are doing, or that it has none. */
    @Test
    fun a_ticket_says_what_its_sessions_are_doing() {
        val rows = mapOf(1L to row(1, "blocked"), 2L to row(2, "working"))
        val busy = ticketLine(Ticket(id = 1, key = "FLEET-142", statusName = "In Progress", liveSessionIds = listOf(1, 2)), null, rows::get)
        assertEquals(StatusWord.NEEDS_YOU, busy.word)
        assertEquals("1 needs you · 1 working", busy.lead)
        assertEquals("In Progress", busy.line)

        val idle = ticketLine(Ticket(id = 2, key = "FLEET-150", statusName = "To Do"), "Personal", rows::get)
        assertNull(idle.lead)
        assertEquals("To Do · no session · Personal", idle.line)
    }
}
