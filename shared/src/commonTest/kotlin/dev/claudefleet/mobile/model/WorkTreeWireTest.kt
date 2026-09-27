package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.WorkTreeWireSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Work view models read what the hub REALLY sends (claude-fleet work
 * graph M14): the samples are the hub's own serialisation, not the spec's
 * prose, so a renamed or reshaped field on either side fails here.
 */
class WorkTreeWireTest {

    @Test
    fun a_real_tree_page_decodes_with_its_provenance_and_one_session_identity() {
        val page = json.decodeFromString(WorkTreePage.serializer(), WorkTreeWireSamples.TREE)
        val keys = page.tasks.mapNotNull { it.key }
        assertTrue(keys.containsAll(listOf("TK-1", "TK-2", "TK-3")), "$keys")
        val tk2 = page.tasks.first { it.key == "TK-2" }
        assertEquals(GroupSource.Manual, tk2.group.source)
        assertEquals("Security", tk2.group.label)
        assertEquals("TP", tk2.group.trackerValue, "what the tracker says stays visible")
        assertEquals(OrgSource.Tracker, tk2.orgSource)
        assertTrue(tk2.orgFenced)
        assertEquals(1L, tk2.placementVersion)
        val tk1 = page.tasks.first { it.key == "TK-1" }
        // One session, under two tasks, by one id; one of them primary.
        assertEquals(tk1.sessions.single().sessionId, tk2.sessions.single().sessionId)
        assertTrue(tk1.sessions.single().primary)
        assertFalse(tk2.sessions.single().primary)
        assertEquals(LinkState.Active, tk2.sessions.single().state)
        assertEquals(1, tk2.sessions.single().otherTasks)
        val tk3 = page.tasks.first { it.key == "TK-3" }
        assertEquals(LinkState.Suggested, tk3.sessions.single().state)
        assertEquals(1, tk3.counts.suggested)
        assertEquals(0, tk3.counts.active)
        assertTrue(page.groups.any { it.group.source == GroupSource.Tracker && it.count == 2 })
        assertEquals(listOf("Acme", "Beta"), page.orgs.map { it.name })
    }

    @Test
    fun a_real_task_detail_decodes_its_placement_and_sessions() {
        val d = json.decodeFromString(TaskDetail.serializer(), WorkTreeWireSamples.TASK)
        assertEquals("item:2", d.task.taskId)
        assertEquals("Security", d.placement?.groupLabel)
        assertEquals("why", d.placement?.note)
        assertEquals(1L, d.placement?.version)
        assertEquals(LinkState.Active, d.task.sessions.single().state)
    }

    @Test
    fun real_session_tasks_carry_each_links_task() {
        val st = json.decodeFromString(SessionTasks.serializer(), WorkTreeWireSamples.SESSION_TASKS)
        assertEquals(2, st.links.size)
        assertEquals(setOf("TK-1", "TK-2"), st.links.mapNotNull { it.task?.key }.toSet())
        assertEquals(1, st.links.count { it.primary })
        assertEquals(st.primaryLinkId, st.links.single { it.primary }.linkId)
    }

    @Test
    fun a_real_review_page_decodes_a_suggestion_with_its_reason() {
        val r = json.decodeFromString(ReviewPage.serializer(), WorkTreeWireSamples.REVIEW)
        val s = r.items.single { it.kind == ReviewKind.Suggestion }
        assertEquals("TK-3", s.task.key)
        assertTrue(s.why.isNotEmpty(), "the reason is visible")
        assertTrue(s.linkVersion > 0)
    }

    @Test
    fun a_real_org_impact_decodes_what_the_move_changes() {
        val i = json.decodeFromString(OrgImpact.serializer(), WorkTreeWireSamples.ORG_IMPACT)
        assertTrue(i.allowed)
        assertEquals(2L, i.toOrg)
        assertTrue(i.links.single().becomesCrossOrg)
        assertEquals(listOf("h1"), i.hostsLosing)
        assertTrue(!i.impactToken.isNullOrBlank())
    }

    /** A session row's `work_rev` (M14): read when sent, 0 when the hub leaves it out. */
    @Test
    fun a_session_rows_work_rev_is_read_and_defaults_to_zero() {
        val sent = json.decodeFromString(SessionRow.serializer(), """{"id":7,"tmux_name":"api","work_rev":8123456789012}""")
        assertEquals(8_123_456_789_012L, sent.workRev)
        val omitted = json.decodeFromString(SessionRow.serializer(), """{"id":7,"tmux_name":"api"}""")
        assertEquals(0L, omitted.workRev)
    }
}
