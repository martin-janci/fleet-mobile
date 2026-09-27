package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.ui.WorkViewJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkFiltersTest {

    @Test
    fun no_filter_is_an_empty_object() {
        assertEquals(JsonObject(emptyMap()), WorkFilters().toJson())
        assertEquals(0, WorkFilters().active)
    }

    /** The hub's `IdOrWord`: an org or tracker id is a number, the words are strings. */
    @Test
    fun ids_are_numbers_and_words_are_strings() {
        val json = WorkFilters(org = OrgChoice.Org(3), tracker = TrackerChoice.Ref).toJson()
        assertEquals(JsonPrimitive(3), json["org"])
        assertEquals(JsonPrimitive("ref"), json["tracker"])
        assertEquals(JsonPrimitive("none"), WorkFilters(org = OrgChoice.Unassigned).toJson()["org"])
    }

    @Test
    fun a_query_is_trimmed_and_capped_at_the_hubs_limit() {
        assertNull(WorkFilters(query = "   ").toJson()["query"])
        assertEquals(WorkFilters.QUERY_MAX, (WorkFilters(query = "x".repeat(300)).toJson()["query"] as JsonPrimitive).content.length)
    }

    @Test
    fun a_saved_views_filters_read_back_as_they_were_written() {
        val f = WorkFilters(
            org = OrgChoice.Org(1),
            tracker = TrackerChoice.Tracker(7),
            status = TaskStatusChoice.Todo,
            mine = true,
            has = TaskHasChoice.None,
            review = true,
            query = "login",
            group = "key:PAY",
        )
        assertEquals(f, WorkFilters.fromJson(f.toJson()))
    }

    /** A string "1" is not the org with id 1: the hub would refuse it, and so a view's `org` reads as no filter. */
    @Test
    fun a_view_is_read_leniently() {
        val odd = buildJsonObject {
            put("org", "1")
            put("tracker", "jira")
            put("status", "blocked")
            put("mine", "yes")
            put("future_filter", true)
        }
        assertEquals(WorkFilters(), WorkFilters.fromJson(odd))
        assertNull(WorkFilters.fromJson(JsonPrimitive("not an object")))
    }

    @Test
    fun the_hubs_views_parse_and_a_broken_one_does_not() {
        val views = WorkViewJson.views()
        assertEquals(WorkFilters(status = TaskStatusChoice.Open, mine = true), views[0].parsed)
        assertEquals(WorkFilters(org = OrgChoice.Org(1), review = true), views[1].parsed)
        assertNull(views[2].parsed)
    }

    @Test
    fun the_hubs_tree_page_decodes_with_fields_the_phone_does_not_read() {
        val page = WorkViewJson.tree(WorkViewJson.TREE_TWO_ORGS)
        assertEquals(listOf("item:70", "item:90"), page.tasks.map { it.taskId })
        assertEquals("c-main", page.nextCursor)
        assertEquals(listOf(1L, 1L, 2L, null), page.groups.map { it.orgId })
        val pay7 = page.tasks[0]
        assertEquals(StatusCategory.InProgress, pay7.statusCategory)
        assertEquals("PAY-7 Refund flow", pay7.label)
        assertEquals(listOf(42L, 41L), pay7.sessions.map { it.linkId })
        assertNull(pay7.sessions[1].sessionId, "an ended link names no live session")
        assertEquals("tracker:1:PAY", pay7.group.id)
    }
}
