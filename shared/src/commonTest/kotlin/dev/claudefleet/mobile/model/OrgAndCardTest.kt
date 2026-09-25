package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What M8.6 reads from the hub beyond the rows: the org directory, a ticket's
 * card, past work in a resume plan, and `org_id` on a row — each from the
 * hub's own wire shape (nulls stripped, unknown keys present).
 */
class OrgAndCardTest {
    @Test
    fun the_org_list_names_orgs_and_maps_trackers_to_them() {
        val details = json.decodeFromString(
            ListSerializer(OrgDetail.serializer()),
            """[{"id":1,"name":"Acme","color":"#f00","isolate_sessions":false,"created_at":1,
                 "rules":[{"id":3,"org_id":1,"owner":"acme"}],"hosts":["pine"],"trackers":[{"id":7,"name":"jira"}]},
                {"id":2,"name":"Side","isolate_sessions":true,"created_at":2,"rules":[],"hosts":[],"trackers":[]}]""",
        )
        val dir = OrgDirectory.of(details)
        assertEquals("Acme", dir.name(1))
        assertEquals("#f00", dir.orgs.getValue(1).color)
        assertEquals("Org 9", dir.name(9), "an org the directory has not heard of still gets a label")
        assertEquals(1L, dir.orgOf(Ticket(id = 70, trackerId = 7)))
        assertNull(dir.orgOf(Ticket(id = 71, trackerId = 8)))
        assertNull(dir.orgOf(Ticket(id = 72)))
    }

    /** A row's org is its own; a hub before M8.6 leaves it out of the phone view, and the work's stands in. */
    @Test
    fun a_rows_org_is_its_own_else_its_works() {
        val row = json.decodeFromString(SessionRow.serializer(), """{"id":1,"org_id":3,"work":{"link_id":1,"title":"","source":"manual","org_id":4}}""")
        assertEquals(3L, row.orgId)
        assertEquals(4L, row.work?.orgId)
        assertEquals(3L, row.orgOf)
        assertEquals(4L, row.copy(orgId = null).orgOf)
        assertNull(SessionRow(id = 2).orgOf)
    }

    @Test
    fun a_card_reads_its_criteria_and_an_uncached_key_says_so() {
        val card = json.decodeFromString(
            TicketCard.serializer(),
            """{"key":"PAY-7","title":"Refund","url":"https://x/PAY-7","status_name":"In Review","status_category":"in_progress",
                "org_id":1,"cached":true,"acceptance":["Retries back off","No double refund"],"composer_text":"Ticket PAY-7: …"}""",
        )
        assertEquals(listOf("Retries back off", "No double refund"), card.acceptance)
        assertTrue(card.hasBody)
        val bare = json.decodeFromString(TicketCard.serializer(), """{"key":"PAY-8","cached":false,"composer_text":"Ticket PAY-8"}""")
        assertFalse(bare.cached)
        assertFalse(bare.hasBody)
        val excerpt = json.decodeFromString(TicketCard.serializer(), """{"key":"PAY-9","cached":true,"excerpt":"Some text"}""")
        assertTrue(excerpt.hasBody)
    }

    @Test
    fun past_work_in_a_resume_plan_carries_its_snapshots() {
        val plan = json.decodeFromString(
            ResumePlan.serializer(),
            """{"key":"PAY-7","modes":[{"mode":"last","ok":true}],"worktree_present":true,
                "candidates":[{"link_id":4,"ended_at":100,"name":"pay","host_alias":"pine","branch":"pay-7-refund",
                "worktree":"/w/pay-7","pr_url":"https://gh/pr/1","conversations":3,"last_claude_session_id":"abc","resumable":true}]}""",
        )
        val past = plan.candidates.single()
        assertEquals(100L, past.endedAt)
        assertEquals("pay-7-refund", past.branch)
        assertEquals(3, past.conversations)
        assertEquals("https://gh/pr/1", past.prUrl)
    }
}
