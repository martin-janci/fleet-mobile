package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A session row exactly as claude-fleet serializes one onto the wire: the
 * store's own `SessionRow` through serde and then `strip_nulls`, which is what
 * both `list_sessions` and `/events` do. Captured from the hub at its M8.0
 * head (a manual `PAY-7` link, plus a suggestion) rather than written by hand,
 * so the keys and their shapes are the hub's and not this test's idea of them.
 */
internal const val STORE_ROW_WITH_WORK = """{"context_stale":false,"created_at":1,"host_alias":"box","id":1,""" +
    """"kind":"work","last_activity_at":1,"project_id":1,"row_version":1,"status":"running","tags":[],""" +
    """"tmux_name":"pay-7","turn_seq":0,"usage_cache_read_tokens":0,"usage_cache_write_tokens":0,""" +
    """"usage_cost_micros":0,"usage_input_tokens":0,"usage_output_tokens":0,""" +
    """"work":{"key":"PAY-7","link_id":1,"source":"manual","state":"confirmed","strength":"explicit","title":""},""" +
    """"work_rejected":[],""" +
    """"work_suggested":{"key":"PAY-9","link_id":12,"rule":"R5","source":"prompt","state":"suggested",""" +
    """"status_category":"in_progress","status_name":"In Review","strength":"weak","suggestions":2,""" +
    """"title":"Refund webhook","url":"https://acme.atlassian.net/browse/PAY-9"}}"""

/** The same row after the link was cleared and the suggestion decided: both keys gone. */
internal const val STORE_ROW_WITHOUT_WORK = """{"context_stale":false,"created_at":1,"host_alias":"box","id":1,""" +
    """"kind":"work","last_activity_at":1,"project_id":1,"row_version":2,"status":"running","tags":[],""" +
    """"tmux_name":"pay-7","turn_seq":0,"usage_cost_micros":0,"work_rejected":["PAY-9"]}"""

class WorkSummaryTest {

    @Test
    fun a_full_store_row_carries_its_work_and_its_suggestion() {
        val row = json.decodeFromString(SessionRow.serializer(), STORE_ROW_WITH_WORK)

        val work = row.work!!
        assertEquals(1L, work.linkId)
        assertEquals("PAY-7", work.label, "a bare key's chip is the key")
        assertEquals("", work.title)
        assertFalse(work.isSuggestion)
        assertEquals("explicit", work.strength)
        assertNull(work.statusCategory, "no tracker status for a bare key")

        val guess = row.workSuggested!!
        assertTrue(guess.isSuggestion)
        assertEquals(12L, guess.linkId, "what Confirm / Not this address")
        assertEquals(StatusCategory.InProgress, guess.statusCategory)
        assertEquals("In Review", guess.statusName)
        assertEquals("R5", guess.rule)
        assertEquals(2, guess.suggestions)
        assertEquals("https://acme.atlassian.net/browse/PAY-9", guess.url)
    }

    @Test
    fun a_row_without_them_has_neither() {
        val row = json.decodeFromString(SessionRow.serializer(), STORE_ROW_WITHOUT_WORK)
        assertNull(row.work)
        assertNull(row.workSuggested)
    }

    /** An older hub's `work` (M1b) has neither state nor status: still a chip. */
    @Test
    fun a_pre_m4_work_summary_parses() {
        val w = json.decodeFromString(WorkSummary.serializer(), """{"link_id":3,"key":"ABC-1","title":"t","source":"branch"}""")
        assertEquals("ABC-1", w.label)
        assertEquals("", w.state)
        assertFalse(w.isSuggestion)
        assertFalse(w.unavailable)
    }

    @Test
    fun an_unknown_status_category_is_unknown_not_a_failed_row() {
        for (wire in listOf("blocked", "", "TODO")) {
            val w = json.decodeFromString(WorkSummary.serializer(), """{"link_id":1,"status_category":"$wire"}""")
            assertEquals(StatusCategory.Unknown, w.statusCategory, wire)
        }
        assertEquals(StatusCategory.Done, StatusCategory.of("done"))
        assertEquals(StatusCategory.Unknown, StatusCategory.of("unknown"))
    }

    @Test
    fun a_ticket_is_unavailable_when_the_hub_says_when_or_why() {
        val base = """{"id":5,"source":"jira","key":"PAY-9","title":"Refund","status_category":"todo","created_at":1,"updated_at":2"""
        assertFalse(json.decodeFromString(Ticket.serializer(), "$base}").unavailable)
        assertTrue(json.decodeFromString(Ticket.serializer(), """$base,"unavailable_at":9}""").unavailable)
        assertTrue(json.decodeFromString(Ticket.serializer(), """$base,"unavailable_reason":"tracker_removed"}""").unavailable)
        val t = json.decodeFromString(Ticket.serializer(), """$base,"live_session_ids":[4,7],"tracker_id":2}""")
        assertEquals(listOf(4L, 7L), t.liveSessionIds)
        assertEquals(StatusCategory.Todo, t.statusCategory)
    }

    /** An older hub answers `resume_plan` with its link list: that must not pass for an empty plan. */
    @Test
    fun a_resume_plan_needs_its_modes() {
        assertFailsWith<Exception> { json.decodeFromString(ResumePlan.serializer(), """{"key":"PAY-9"}""") }
        val plan = json.decodeFromString(
            ResumePlan.serializer(),
            """{"key":"PAY-9","live":[{"session_id":4,"host_alias":"h","tmux_name":"t"}],""" +
                """"modes":[{"mode":"last","ok":true},{"mode":"fresh","ok":false,"reason":"x"}],"hosts":["h","g"]}""",
        )
        assertTrue(plan.can("last"))
        assertFalse(plan.can("fresh"))
        assertFalse(plan.can("brief"))
        assertEquals(4L, plan.live.single().sessionId)
    }
}
