package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The active-filter summary: one chip per filter that narrows the list, in
 * the desktop's words. The cases follow claude-fleet's
 * `src/lib/filter_facets.test.ts`, so a label that drifts on either side
 * fails here.
 */
class FilterFacetsTest {

    // ── Sessions list ──

    @Test
    fun session_facets_are_empty_when_nothing_narrows_the_list() {
        assertEquals(emptyList(), sessionFacets(SessionFilters()))
        // Showing archived sessions widens the list; it is not a facet either.
        assertEquals(emptyList(), sessionFacets(SessionFilters(showArchived = true)))
    }

    @Test
    fun session_facets_name_every_filter_that_narrows_in_reading_order() {
        val f = SessionFilters(
            needsAttentionOnly = true,
            orgFilter = 1,
            hostFilter = "gpu-box",
            projectFilter = 9,
            window = TimeWindow.D1,
            query = "  login ",
            statuses = setOf(StatusFilter.FAILED, StatusFilter.WORKING),
            workStatuses = setOf(WorkStatusFilter.IN_PROGRESS),
            myWorkOnly = true,
            showBackground = false,
        )
        val facets = sessionFacets(f, orgName = { if (it == 1L) "Acme" else null }, projectName = { "acme/api" })
        assertEquals(
            listOf(
                "Needs you",
                "Org: Acme",
                "Host: gpu-box",
                "Project: acme/api",
                "Active within 24 hours",
                "Search: “login”",
                "State: Working/Failed",
                "Status: In progress",
                "Assigned to me",
                "Background agents hidden",
            ),
            facets.map { it.label },
        )
        assertEquals(SessionFacetId.entries.toList(), facets.map { it.id }, "one facet per id, in declaration order")
    }

    @Test
    fun an_org_or_project_without_a_name_reads_as_its_id() {
        val labels = sessionFacets(SessionFilters(orgFilter = 3, projectFilter = 4)).map { it.label }
        assertEquals(listOf("Org: #3", "Project: #4"), labels)
    }

    /** The desktop reads a tracker column alone as a column, not a status. */
    @Test
    fun a_tracker_column_reads_as_a_column() {
        val f = SessionFilters(workStatusNames = setOf("QA Review"))
        assertEquals(listOf(Facet(SessionFacetId.WORK_STATUS, "Column: QA Review")), sessionFacets(f))
    }

    @Test
    fun only_needs_you_and_the_search_have_their_own_control_on_screen() {
        assertEquals(setOf(SessionFacetId.NEEDS_YOU, SessionFacetId.SEARCH), SessionFacetId.entries.filter { it.onScreen }.toSet())
    }

    /** Clearing a facet resets that one field to its default and keeps the rest. */
    @Test
    fun clearing_a_session_facet_resets_only_its_own_field() {
        val all = SessionFilters(
            needsAttentionOnly = true,
            orgFilter = 1,
            hostFilter = "gpu-box",
            projectFilter = 9,
            window = TimeWindow.D1,
            direction = TimeDirection.BEYOND,
            query = "login",
            statuses = setOf(StatusFilter.FAILED),
            workStatuses = setOf(WorkStatusFilter.DONE),
            workStatusNames = setOf("QA Review"),
            myWorkOnly = true,
            showBackground = false,
            showArchived = true,
        )
        for (id in SessionFacetId.entries) {
            val left = all.without(id)
            assertEquals(
                sessionFacets(all).map { it.id } - id,
                sessionFacets(left).map { it.id },
                "clearing $id cleared exactly that facet",
            )
            assertTrue(left.showArchived, "no facet touches Show archived")
        }
        val time = all.without(SessionFacetId.TIME)
        assertEquals(TimeWindow.ANY, time.window)
        assertEquals(TimeDirection.WITHIN, time.direction, "a direction with no window is reset with it")
        val status = all.without(SessionFacetId.WORK_STATUS)
        assertTrue(status.workStatuses.isEmpty() && status.workStatusNames.isEmpty(), "buckets and columns are one question")
        assertEquals(null, all.without(SessionFacetId.HOST).hostFilter)
    }

    // ── My work ──

    @Test
    fun work_facets_name_org_tracker_status_mine_sessions_review_and_search() {
        val f = WorkTreeFilters(
            org = IdOrWord.NONE,
            tracker = IdOrWord.LOCAL,
            status = "open",
            mine = true,
            has = "none",
            review = true,
            query = "pay",
        )
        assertEquals(
            listOf(
                "Org: Unassigned",
                "Tracker: Local work",
                "Status: Open",
                "Assigned to me",
                "Sessions: No session",
                "To review",
                "Search: “pay”",
            ),
            workFacets(f).map { it.label },
        )
        assertEquals(
            listOf("Org: Acme", "Tracker: Linear"),
            workFacets(
                WorkTreeFilters(org = IdOrWord.of(3), tracker = IdOrWord.of(4)),
                orgName = { "Acme" },
                trackerName = { "Linear" },
            ).map { it.label },
        )
        assertEquals(listOf("Tracker: Bare keys", "Sessions: Active session"), workFacets(WorkTreeFilters(tracker = IdOrWord.REF, has = "active")).map { it.label })
        assertEquals(listOf("Org: #3", "Tracker: #4"), workFacets(WorkTreeFilters(org = IdOrWord.of(3), tracker = IdOrWord.of(4))).map { it.label })
    }

    @Test
    fun defaults_are_not_filters_and_group_and_archived_are_not_either() {
        val f = WorkTreeFilters(status = "any", has = "any", mine = false, query = " ", group = "x", archived = true)
        assertEquals(emptyList(), workFacets(f))
    }

    @Test
    fun removes_one_work_facet_and_keeps_the_rest() {
        val f = WorkTreeFilters(org = IdOrWord.of(1), status = "done", query = "x", archived = true)
        assertEquals(WorkTreeFilters(org = IdOrWord.of(1), query = "x", archived = true), f.without(WorkFacetId.STATUS))
        for (id in WorkFacetId.entries) {
            val all = WorkTreeFilters(org = IdOrWord.of(1), tracker = IdOrWord.REF, status = "todo", mine = true, has = "past_only", review = true, query = "q")
            assertEquals(workFacets(all).map { it.id } - id, workFacets(all.without(id)).map { it.id })
        }
    }

    @Test
    fun the_strip_leaves_out_search_and_the_two_toggles() {
        assertEquals(setOf(WorkFacetId.MINE, WorkFacetId.REVIEW, WorkFacetId.QUERY), WorkFacetId.entries.filter { it.onScreen }.toSet())
        assertFalse(WorkFacetId.ORG.onScreen)
    }

    @Test
    fun facets_read_as_one_sentence_for_an_empty_state() {
        assertEquals("Host: x, Last 1d", facetSentence(listOf(Facet("a", "Host: x"), Facet("b", "Last 1d"))))
        assertEquals("", facetSentence(emptyList()))
    }
}
