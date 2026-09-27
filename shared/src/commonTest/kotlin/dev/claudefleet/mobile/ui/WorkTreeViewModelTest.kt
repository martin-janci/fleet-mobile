@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.OrgChoice
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TaskHasChoice
import dev.claudefleet.mobile.model.TaskStatusChoice
import dev.claudefleet.mobile.model.TrackerChoice
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkTreeViewModelTest {

    /** 2026-09-27 10:42 UTC, read on a clock two hours ahead: 12:42 local. */
    private val tenFortyTwo = 1_790_505_720L

    private fun vm(
        fleet: WorkFleet,
        actions: FakeWorkViewActions,
        scope: CoroutineScope,
        clock: () -> Long = { tenFortyTwo },
    ) = WorkTreeViewModel(fleet, actions, scope, clock = clock, utcOffset = { 0 }, debounceMs = 1_000)

    private fun fleet(caps: HubCapabilities = WorkViewJson.WORK_VIEW_CAPS, rows: List<SessionRow> = emptyList()) =
        WorkFleet(rows = rows, caps = caps)

    private fun answeringTwoOrgs() = FakeWorkViewActions().apply {
        treeAnswer = { call ->
            if (call.filters["group"] != null) WorkViewJson.tree(WorkViewJson.TREE_PAY_SECTION) else WorkViewJson.tree(WorkViewJson.TREE_TWO_ORGS)
        }
        viewsAnswer = WorkViewJson.views()
    }

    // ---- gating ----

    /** A hub before M14: `work` is there, its enum has no `tree`. The tab is hidden and nothing is asked. */
    @Test
    fun an_older_hub_hides_the_tab_and_is_never_asked() = runTest {
        val older = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work" to setOf("tickets", "lookup", "today"))))
        val actions = FakeWorkViewActions()
        val tree = vm(fleet(older), actions, backgroundScope)

        assertFalse(tree.state.value.available)
        assertNull(tree.onFocus())
        runCurrent()
        assertEquals(emptyList(), actions.treeCalls)
    }

    /**
     * A hub whose `work` action is a free string (before M8.0) is "maybe"
     * for every other feature; for the tab it is "no" — the tab must not
     * appear and then be refused.
     */
    @Test
    fun a_free_string_action_is_not_a_listed_tree() = runTest {
        val freeString = HubCapabilities.of(ToolCatalog(setOf("work")))
        assertTrue(freeString.has("work", "tree"), "the old, lenient gate would have said yes")
        assertFalse(freeString.workView)
        assertFalse(vm(fleet(freeString), FakeWorkViewActions(), backgroundScope).state.value.available)
    }

    @Test
    fun no_work_tool_at_all_hides_the_tab() = runTest {
        assertFalse(vm(fleet(HubCapabilities()), FakeWorkViewActions(), backgroundScope).state.value.available)
    }

    /** A hub that refuses `tree` as unknown after all: forgotten for the connection, which hides the tab. */
    @Test
    fun a_refused_tree_hides_the_tab_for_the_connection() = runTest {
        val f = fleet()
        val actions = FakeWorkViewActions().apply { failTree = HubError.Tool("E_INVALID", "unknown work action \"tree\"") }
        val tree = vm(f, actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        assertFalse(f.capabilities.value.workView)
        assertFalse(tree.state.value.available)
    }

    // ---- the tree and its paging ----

    @Test
    fun focus_reads_the_first_page_and_draws_org_then_group_sections() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        val s = tree.state.value
        assertTrue(s.loaded)
        assertEquals(7, s.total)
        assertEquals(listOf("Acme", "Globex", "Unassigned"), s.orgs.map { it.name })
        assertEquals(listOf(4, 2, 1), s.orgs.map { it.count })
        val acme = s.orgs[0].sections
        assertEquals(listOf("PAY", "No group"), acme.map { it.group.label })
        assertEquals(listOf("PAY-7", "PAY-9"), acme[0].tasks.map { it.key })
        assertTrue(acme[0].canLoadMore, "two of three loaded")
        assertTrue(acme[1].tasks.isEmpty() && acme[1].canLoadMore, "a header with a count and nothing loaded yet")
        assertEquals(listOf(TreeCall(JsonObject(emptyMap()), null, WorkTreeViewModel.PAGE)), actions.treeCalls)
        assertEquals(1, actions.viewsCalls)
    }

    /**
     * *Load more* asks for its own section: the first time from the top (the
     * main page's cursor belongs to other filters), with its org and group
     * added to the filters in force; after that by the section's own cursor.
     */
    @Test
    fun load_more_reads_its_section_from_the_top_then_by_its_own_cursor() = runTest {
        var sectionCursor: String? = "c-pay-2"
        val actions = answeringTwoOrgs().apply {
            treeAnswer = { call ->
                when {
                    call.filters["group"] == null -> WorkViewJson.tree(WorkViewJson.TREE_TWO_ORGS)
                    call.cursor == null -> WorkViewJson.tree(WorkViewJson.TREE_PAY_SECTION).copy(nextCursor = sectionCursor)
                    else -> WorkViewJson.tree(WorkViewJson.TREE_PAY_SECTION).copy(nextCursor = null)
                }
            }
        }
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        tree.setStatus(TaskStatusChoice.Open)
        runCurrent()

        val pay = WorkSectionKey(1, "tracker:1:PAY")
        tree.loadMore(pay)
        runCurrent()
        val first = actions.treeCalls.last()
        assertEquals(
            buildJsonObject { put("org", 1); put("status", "open"); put("group", "tracker:1:PAY") },
            first.filters,
        )
        assertNull(first.cursor)
        assertEquals(2 + WorkTreeViewModel.PAGE, first.limit, "what it showed plus a page")
        val section = tree.state.value.orgs[0].sections[0]
        assertEquals(listOf("PAY-7", "PAY-9", "PAY-11"), section.tasks.map { it.key })
        assertTrue(section.canLoadMore, "the hub answered a cursor")

        sectionCursor = null
        tree.loadMore(pay)
        runCurrent()
        assertEquals("c-pay-2", actions.treeCalls.last().cursor)
        assertEquals(WorkTreeViewModel.PAGE, actions.treeCalls.last().limit)
        val after = tree.state.value.orgs[0].sections[0]
        assertEquals(listOf("PAY-7", "PAY-9", "PAY-11"), after.tasks.map { it.key }, "a repeated task is not drawn twice")
        assertFalse(after.canLoadMore)
    }

    /** Unassigned work's section is asked for as `org: "none"`, never as a missing org. */
    @Test
    fun the_unassigned_section_pages_as_org_none() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        tree.loadMore(WorkSectionKey(null, "none"))
        runCurrent()
        assertEquals(buildJsonObject { put("org", "none"); put("group", "none") }, actions.treeCalls.last().filters)
    }

    /** A refresh keeps a section someone grew: it is read again to as many tasks as it showed. */
    @Test
    fun a_refresh_re_reads_a_grown_section_to_its_size() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        tree.loadMore(WorkSectionKey(1, "tracker:1:PAY"))
        runCurrent()
        actions.treeCalls.clear()

        tree.pull()
        runCurrent()

        val again = actions.treeCalls.single { it.filters["group"] != null }
        assertEquals(3, again.limit)
        assertNull(again.cursor)
        assertEquals(3, tree.state.value.orgs[0].sections[0].tasks.size)
        assertFalse(tree.state.value.refreshing)
    }

    @Test
    fun folding_an_org_or_a_section_is_local() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        val calls = actions.treeCalls.size

        tree.toggleOrg(2)
        tree.toggleSection(WorkSectionKey(1, "none"))
        runCurrent()
        val s = tree.state.value
        assertTrue(s.orgs[1].collapsed)
        assertTrue(s.orgs[0].sections[1].collapsed)
        assertFalse(s.orgs[0].sections[0].collapsed)
        assertEquals(calls, actions.treeCalls.size)
    }

    // ---- filters → request ----

    @Test
    fun every_filter_reaches_the_request_and_only_those_set() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        tree.setOrg(OrgChoice.Org(2)); runCurrent()
        tree.setTracker(TrackerChoice.Local); runCurrent()
        tree.setStatus(TaskStatusChoice.InProgress); runCurrent()
        tree.toggleMine(); runCurrent()
        tree.setHas(TaskHasChoice.PastOnly); runCurrent()
        tree.toggleReview(); runCurrent()
        tree.setQuery("  login ")
        advanceTimeBy(WorkTreeViewModel.QUERY_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(
            buildJsonObject {
                put("org", 2)
                put("tracker", "local")
                put("status", "in_progress")
                put("mine", true)
                put("has", "past_only")
                put("review", true)
                put("query", "login")
            },
            actions.treeCalls.last().filters,
        )
        assertNull(actions.treeCalls.last().cursor, "new filters start from the top")
        assertEquals(7, tree.state.value.filters.active)

        tree.setOrg(OrgChoice.Unassigned); runCurrent()
        tree.setTracker(TrackerChoice.Tracker(3)); runCurrent()
        assertEquals("none", actions.treeCalls.last().filters["org"].toString().trim('"'))
        assertEquals("3", actions.treeCalls.last().filters["tracker"].toString())

        tree.clearFilters(); runCurrent()
        assertEquals(JsonObject(emptyMap()), actions.treeCalls.last().filters)
        assertEquals(0, tree.state.value.filters.active)
    }

    /** Typing is one read once it pauses, not one per key. */
    @Test
    fun the_search_box_reads_once_typing_pauses() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        val before = actions.treeCalls.size

        tree.setQuery("l"); tree.setQuery("lo"); tree.setQuery("log")
        advanceTimeBy(WorkTreeViewModel.QUERY_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(before + 1, actions.treeCalls.size)
        assertEquals(buildJsonObject { put("query", "log") }, actions.treeCalls.last().filters)
    }

    /** New filters drop every section's pages: they were answers to the old ones. */
    @Test
    fun new_filters_forget_the_old_sections_pages() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        tree.loadMore(WorkSectionKey(1, "tracker:1:PAY"))
        runCurrent()
        actions.treeCalls.clear()

        tree.toggleMine()
        runCurrent()
        assertEquals(1, actions.treeCalls.size, "only the first page: no section is re-grown")
        assertEquals(2, tree.state.value.orgs[0].sections[0].tasks.size)
    }

    /** A saved view's chip applies its filters as they are; tapping it again clears them. Nothing is saved. */
    @Test
    fun a_saved_view_chip_applies_its_filters_read_only() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        val views = tree.state.value.views
        assertEquals(listOf("My open work", "Acme review"), views.map { it.name }, "a view whose filters cannot be read is not offered")
        tree.applyView(views[0])
        runCurrent()
        assertEquals(buildJsonObject { put("status", "open"); put("mine", true) }, actions.treeCalls.last().filters)
        assertEquals(1L, tree.state.value.activeViewId)

        tree.applyView(views[1])
        runCurrent()
        assertEquals(
            buildJsonObject { put("org", 1); put("review", true) },
            actions.treeCalls.last().filters,
            "a word this build does not know reads as no filter",
        )

        tree.applyView(views[1])
        runCurrent()
        assertEquals(JsonObject(emptyMap()), actions.treeCalls.last().filters)
        assertNull(tree.state.value.activeViewId)
    }

    /** A hub that lists `tree` but not `views`: no chips, and `views` is never asked. */
    @Test
    fun no_views_action_means_no_chips() = runTest {
        val caps = HubCapabilities.of(ToolCatalog(setOf("work"), mapOf("work" to setOf("tree", "task"))))
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(caps), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        assertEquals(0, actions.viewsCalls)
        assertTrue(tree.state.value.views.isEmpty())
    }

    // ---- the stale banner ----

    @Test
    fun offline_shows_the_time_the_picture_was_read() = runTest {
        val f = fleet()
        val tree = vm(f, answeringTwoOrgs(), backgroundScope)
        tree.onFocus()
        runCurrent()
        assertNull(tree.state.value.stale, "connected: no banner")

        f.status.value = ConnectionStatus.Offline("no network")
        runCurrent()
        assertEquals("Offline · as of 10:42", tree.state.value.stale)
        assertEquals(listOf("Acme", "Globex", "Unassigned"), tree.state.value.orgs.map { it.name }, "the last picture stays")

        f.status.value = ConnectionStatus.Reconnecting(2, null)
        runCurrent()
        assertEquals("Offline · as of 10:42", tree.state.value.stale, "reconnecting is not connected")
    }

    @Test
    fun offline_before_any_read_says_only_offline() = runTest {
        val f = fleet().apply { status.value = ConnectionStatus.Offline("no network") }
        val tree = vm(f, FakeWorkViewActions(), backgroundScope)
        assertEquals("Offline", tree.state.value.stale)
    }

    /** A read that fails while offline keeps the picture and adds no second banner: the stale one says it. */
    @Test
    fun a_failed_read_while_offline_keeps_the_picture_quietly() = runTest {
        val f = fleet()
        val actions = answeringTwoOrgs()
        val tree = vm(f, actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        f.status.value = ConnectionStatus.Offline("no network")
        actions.failTree = HubError.Transport(IllegalStateException("unreachable"))
        tree.pull()
        runCurrent()

        assertNull(tree.state.value.error)
        assertTrue(tree.state.value.loaded)
        assertEquals("Offline · as of 10:42", tree.state.value.stale)
    }

    @Test
    fun the_stream_coming_back_re_reads_and_clears_the_banner() = runTest {
        var now = tenFortyTwo
        val f = fleet()
        val actions = answeringTwoOrgs()
        val tree = vm(f, actions, backgroundScope, clock = { now })
        tree.onFocus()
        runCurrent()
        f.status.value = ConnectionStatus.Offline("no network")
        runCurrent()
        val before = actions.treeCalls.size

        now += 600
        f.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()

        assertEquals(before + 1, actions.treeCalls.size)
        assertNull(tree.state.value.stale)
    }

    @Test
    fun clock_time_is_the_local_hour_and_minute() {
        assertEquals("10:42", clockTime(tenFortyTwo, 0))
        assertEquals("12:42", clockTime(tenFortyTwo, 7_200))
        assertEquals("23:42", clockTime(tenFortyTwo, -39_600))
    }

    // ---- refresh: focus, pull, session changes — no work:changed ----

    @Test
    fun focus_reads_again_every_time() = runTest {
        val actions = answeringTwoOrgs()
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus(); runCurrent()
        tree.onBlur()
        tree.onFocus(); runCurrent()
        assertEquals(2, actions.treeCalls.size)
    }

    /**
     * A bound phone gets no `work:changed` (claude-fleet #347), so a session
     * change is what moves the tab: debounced into one read when it touches a
     * session the tab shows or one with work, ignored otherwise, and ignored
     * altogether while the tab is not on screen.
     */
    @Test
    fun relevant_session_changes_refresh_once_debounced() = runTest {
        val scratch = SessionRow(id = 30, tmuxName = "scratch")
        val linked = SessionRow(id = 31, tmuxName = "ops", work = WorkSummary(linkId = 9, key = "OPS-1"))
        val f = fleet(rows = listOf(scratch, linked))
        val actions = answeringTwoOrgs()
        val tree = vm(f, actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        val before = actions.treeCalls.size

        f.sessionChanges.emit(30)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(before, actions.treeCalls.size, "a session with no work the tab does not show")

        f.sessionChanges.emit(7) // shown under PAY-7
        f.sessionChanges.emit(31) // carries work: it may have just been linked
        f.sessionChanges.emit(7)
        advanceTimeBy(500); runCurrent()
        assertEquals(before, actions.treeCalls.size, "still gathering")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(before + 1, actions.treeCalls.size, "one read for the burst")

        f.sessionChanges.emit(ALL_SESSIONS_CHANGED)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(before + 2, actions.treeCalls.size, "a resync is always relevant")

        tree.onBlur()
        f.sessionChanges.emit(7)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(before + 2, actions.treeCalls.size, "off screen, a change costs nothing")
    }

    @Test
    fun a_failed_read_while_connected_says_so() = runTest {
        val actions = FakeWorkViewActions().apply { failTree = HubError.Tool("E_INVALID", "filters.status is any, open, todo, in_progress or done") }
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()
        assertEquals("The hub refused that", tree.state.value.error?.title)
        tree.dismissError()
        runCurrent()
        assertNull(tree.state.value.error)
    }

    // ---- a phone bound to one org ----

    /**
     * `fleet-hub pair --org 1`, the org's D31 flag off: the hub answers Acme
     * only. The tab draws exactly that — its filter offers Acme and nothing
     * it was not told of, no *Unassigned*, no other org's tracker — and no
     * request names an org the hub did not list.
     */
    @Test
    fun a_phone_bound_to_one_org_assumes_no_other_org_exists() = runTest {
        val actions = FakeWorkViewActions().apply {
            treeAnswer = { WorkViewJson.tree(WorkViewJson.TREE_BOUND_TO_ACME) }
            viewsAnswer = WorkViewJson.views("""[{"id":2,"name":"Acme review","filters":{"org":1,"review":true},"owner_org":1,"version":2,"updated_at":1}]""")
        }
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        val s = tree.state.value
        assertEquals(listOf("Acme"), s.orgs.map { it.name })
        assertEquals(listOf(1L), s.orgChoices.map { it.id })
        assertFalse(s.offerUnassigned, "the hub showed no unassigned work to this phone")
        assertEquals(listOf("Acme Jira"), s.trackerChoices.map { it.name })
        assertEquals(1, s.total)
        assertFalse(s.orgs.single().sections.single().canLoadMore)
        assertEquals(listOf("Acme review"), s.views.map { it.name })

        tree.applyView(s.views.single())
        runCurrent()
        val orgsAsked = actions.treeCalls.mapNotNull { it.filters["org"]?.toString() }.toSet()
        assertEquals(setOf("1"), orgsAsked)
    }

    /** The same phone with D31 on: unassigned work is part of its picture, and only then offered as a filter. */
    @Test
    fun a_bound_phone_that_sees_unassigned_work_is_offered_it() = runTest {
        val withUnassigned = WorkViewJson.tree(WorkViewJson.TREE_BOUND_TO_ACME).let { page ->
            page.copy(groups = page.groups + dev.claudefleet.mobile.model.TreeGroup(orgId = null, count = 2), total = 3)
        }
        val actions = FakeWorkViewActions().apply { treeAnswer = { withUnassigned } }
        val tree = vm(fleet(), actions, backgroundScope)
        tree.onFocus()
        runCurrent()

        assertEquals(listOf("Acme", "Unassigned"), tree.state.value.orgs.map { it.name })
        assertTrue(tree.state.value.offerUnassigned)
        assertEquals(listOf(1L), tree.state.value.orgChoices.map { it.id })
    }
}
