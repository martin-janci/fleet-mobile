package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.WorkTreeJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Work view's answers decode from the contract's own JSON, and every unknown value degrades rather than failing. */
class WorkTreeTest {

    @Test
    fun a_tree_page_decodes_its_tasks_groups_and_cursor() {
        val page = json.decodeFromString(WorkTreePage.serializer(), WorkTreeJson.TREE)

        assertEquals(listOf("item:12", "item:13", "item:77", "ref:OLD-1"), page.tasks.map { it.taskId })
        assertEquals(5, page.groups.size)
        assertEquals("c1.abc", page.nextCursor)
        assertEquals(1_790_000_300L, page.generatedAt)
        assertEquals(8, page.total)
        val abc = page.tasks[0]
        assertEquals(GroupSource.Tracker, abc.group.source)
        assertEquals("ABC", abc.group.trackerValue)
        assertEquals(OrgSource.Tracker, abc.orgSource)
        assertTrue(abc.orgFenced)
        assertEquals(TaskCounts(active = 1, ended = 2, suggested = 1), abc.counts)
        assertEquals(LinkState.Active, abc.sessions.single().state)
        assertTrue(abc.sessions.single().primary)
        assertEquals(3L, abc.sessions.single().linkVersion)
        assertEquals(listOf("Acme", "Globex"), page.orgs.map { it.name })
        assertEquals(1L, page.trackers.single().orgId)
    }

    @Test
    fun unknown_enum_values_read_as_unknown_and_absent_fields_default() {
        val page = json.decodeFromString(WorkTreePage.serializer(), WorkTreeJson.TREE)

        assertEquals(GroupSource.Unknown, page.groups.last().group.source, "a source this build does not know")
        assertEquals(StatusCategory.Unknown, page.tasks.last().statusCategory)
        val bare = page.tasks.last()
        assertEquals(TaskKind.Ref, bare.kind)
        assertNull(bare.orgId, "the hub strips null: absent reads as none")
        assertEquals(0, bare.counts.active)
        assertEquals(TaskKind.Local, page.tasks[2].kind)
        assertEquals(4L, page.tasks[2].placementVersion)
    }

    @Test
    fun a_task_is_tracker_down_only_when_its_tracker_says_so() {
        val page = json.decodeFromString(WorkTreePage.serializer(), WorkTreeJson.TREE)

        assertFalse(page.tasks[0].trackerDown)
        assertTrue(page.tasks[1].trackerDown)
        assertFalse(page.tasks[2].trackerDown, "local work has no tracker to be down")
    }

    @Test
    fun a_task_detail_decodes_every_session_state_and_its_placement() {
        val detail = json.decodeFromString(TaskDetail.serializer(), WorkTreeJson.TASK)

        assertEquals(
            listOf(LinkState.Active, LinkState.Suggested, LinkState.Ended, LinkState.Rejected),
            detail.task.sessions.map { it.state },
        )
        assertNull(detail.task.sessions[2].sessionId, "an ended link has no live session")
        assertEquals(listOf("ref:ABC-12"), detail.aliases)
        assertEquals("Payments", detail.placement?.groupLabel)
        assertEquals(2L, detail.placement?.version)
        assertEquals(listOf(3L), detail.rules)
        assertEquals("abc-12-v1", detail.lastOutcome?.branch)
        assertEquals("branch", detail.task.sessions[0].evidence.single().jsonObject["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_placement_group_given_as_an_object_still_reads_its_label() {
        val p = json.decodeFromString(Placement.serializer(), """{"group":{"id":"manual:Ops","label":"Ops"},"version":1}""")
        assertEquals("Ops", p.groupLabel)
    }

    @Test
    fun session_tasks_review_rules_views_impact_and_batch_decode() {
        val tasks = json.decodeFromString(SessionTasks.serializer(), WorkTreeJson.SESSION_TASKS)
        assertEquals(42L, tasks.primaryLinkId)
        assertEquals(listOf("ABC-12", "OPS-1", "ABC-15", "ABC-9"), tasks.links.map { it.task?.label })
        assertTrue(tasks.links.last().task!!.unavailable)

        val review = json.decodeFromString(ReviewPage.serializer(), WorkTreeJson.REVIEW)
        assertEquals(listOf(ReviewKind.Suggestion, ReviewKind.CrossOrg, ReviewKind.Unknown), review.items.map { it.kind })
        assertEquals("ABC-13", review.items[0].alternatives.single().label)
        assertEquals("Ops cleanup", review.items[1].task.label, "no key: the title labels it")

        val rules = json.decodeFromString(ListSerializer(WorkRule.serializer()), WorkTreeJson.RULES)
        assertEquals("PAY", rules.single().conditions.container)

        val views = json.decodeFromString(ListSerializer(WorkView.serializer()), WorkTreeJson.VIEWS)
        assertEquals(IdOrWord.of(1), views[0].filters.org)
        assertEquals(IdOrWord.NONE, views[1].filters.org)
        assertEquals(IdOrWord.LOCAL, views[1].filters.tracker)

        val impact = json.decodeFromString(OrgImpact.serializer(), WorkTreeJson.ORG_IMPACT)
        assertTrue(impact.links.single().becomesCrossOrg)
        assertEquals("tok-impact", impact.impactToken)

        val batch = json.decodeFromString(BatchResult.serializer(), WorkTreeJson.BATCH)
        assertEquals(listOf(true, false), batch.results.map { it.ok })
        assertEquals("E_CONFLICT", batch.results[1].code)
    }

    /** An org id goes back to the hub as a number, a word as a string — never `"3"`. */
    @Test
    fun filters_encode_an_id_as_a_number_and_a_word_as_a_string_and_drop_any() {
        val encoded = json.encodeToJsonElement(
            WorkTreeFilters.serializer(),
            WorkTreeFilters(org = IdOrWord.of(3), tracker = IdOrWord.REF, status = "any", mine = false, query = "  ", has = "active").normalized(),
        ).jsonObject

        assertEquals(3L, encoded["org"]!!.jsonPrimitive.long)
        assertFalse(encoded["org"]!!.jsonPrimitive.isString)
        assertEquals("ref", encoded["tracker"]!!.jsonPrimitive.content)
        assertTrue(encoded["tracker"]!!.jsonPrimitive.isString)
        assertEquals(setOf("org", "tracker", "has"), encoded.keys, "any, false and a blank query are left out")
    }

    @Test
    fun the_filter_count_leaves_out_search_and_group() {
        assertEquals(0, WorkTreeFilters(query = "login", group = "none").count)
        assertEquals(2, WorkTreeFilters(mine = true, status = "open").count)
        assertTrue(WorkTreeFilters(status = "any", mine = false).isEmpty)
    }

    /**
     * The *Filters (n)* badge counts what the sheet holds; the two toggles on
     * screen, the search and *Show archived* (it widens) are not counted.
     */
    @Test
    fun the_sheet_count_is_org_tracker_status_and_sessions_only() {
        val all = WorkTreeFilters(
            org = IdOrWord.NONE,
            tracker = IdOrWord.LOCAL,
            status = "done",
            has = "none",
            mine = true,
            review = true,
            query = "x",
            archived = true,
        )
        assertEquals(4, all.sheetCount)
        assertEquals(6, all.count)
        assertEquals(0, WorkTreeFilters(archived = true).count)
        assertTrue(WorkTreeFilters(archived = true).isEmpty, "showing archived tasks narrows nothing")
        assertFalse(WorkTreeFilters(query = "x").isEmpty)
    }

    /**
     * A status or sessions value this build does not offer — remembered by an
     * older build, or saved in a view by a newer desktop — is dropped, as the
     * desktop's `normalizeFilters` drops it: sent on, it narrowed the tree
     * with no chip selected in the sheet to show it or clear it.
     */
    @Test
    fun a_status_or_sessions_value_this_build_does_not_offer_is_dropped() {
        val n = WorkTreeFilters(status = "blocked", has = "someday", org = IdOrWord.of(3)).normalized()
        assertEquals(WorkTreeFilters(org = IdOrWord.of(3)), n)
        for (s in WorkTreeFilters.STATUSES) assertEquals(s, WorkTreeFilters(status = s).normalized().status)
        for (h in WorkTreeFilters.HAS) assertEquals(h, WorkTreeFilters(has = h).normalized().has)
    }

    /** The desktop's labels (`STATUS_FILTER_LABELS`, `HAS_FILTER_LABELS`), sentence case, *Any* first. */
    @Test
    fun the_labels_are_the_desktops() {
        assertEquals(
            listOf("Any", "Open", "To do", "In progress", "Done"),
            (listOf(WorkTreeFilters.ANY) + WorkTreeFilters.STATUSES).map(WorkTreeFilters::statusLabel),
        )
        assertEquals(
            listOf("Any", "Active session", "Past only", "No session", "Suggested"),
            (listOf(WorkTreeFilters.ANY) + WorkTreeFilters.HAS).map(WorkTreeFilters::hasLabel),
        )
    }
}
