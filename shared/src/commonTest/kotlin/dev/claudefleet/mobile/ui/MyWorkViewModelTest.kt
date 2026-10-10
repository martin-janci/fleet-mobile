@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.ReviewPage
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.IdOrWord
import dev.claudefleet.mobile.model.WorkTreeFilters
import dev.claudefleet.mobile.model.WorkTreePage
import dev.claudefleet.mobile.model.WorkView
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun page(text: String): WorkTreePage = json.decodeFromString(WorkTreePage.serializer(), text)

private const val DEBOUNCE = 2_000L

private fun TestScope.myWork(
    fleet: WorkFleet = WorkFleet(caps = WorkFleet.WORK_VIEW),
    actions: FakeWorkActions = FakeWorkActions().answeringTree(),
    canWrite: Boolean = true,
    prefs: Prefs? = FakePrefs(),
    scope: CoroutineScope = backgroundScope,
) = MyWorkViewModel(
    fleet = fleet,
    actions = actions,
    scope = scope,
    canWrite = canWrite,
    prefs = prefs,
    clock = { 1_790_000_999 },
    utcOffset = { 0 },
    refreshDebounceMs = DEBOUNCE,
)

/** The recorded tree for the whole view; the ABC section's own page for a section read. */
private fun FakeWorkActions.answeringTree(): FakeWorkActions = apply {
    treeAnswer = { filters, _ ->
        if (filters.group == "tracker:1:ABC") page(WorkTreeJson.TREE_ABC_PAGE_TWO) else page(WorkTreeJson.TREE)
    }
}

class MyWorkViewModelTest {

    /** Sections come from the hub's headers — every one, with its count — and the tasks fill them in order. */
    @Test
    fun sections_are_the_hubs_org_and_group_headers_with_counts() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions)

        vm.attach()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.available && s.loaded)
        assertEquals(listOf("Acme", "Globex", "Unassigned"), s.orgs.map { it.name }, "the desktop's word for no org")
        assertEquals(listOf(4, 1, 3), s.orgs.map { it.count }, "an org counts every task of its groups, loaded or not")
        val abc = s.orgs[0].groups[0]
        assertEquals("ABC", abc.group.title)
        assertEquals(3, abc.count)
        assertEquals(listOf("item:12", "item:13"), abc.tasks.map { it.taskId })
        assertTrue(abc.hasMore, "3 in the section, 2 loaded")
        val payments = s.orgs[0].groups[1]
        assertTrue(payments.tasks.isEmpty() && payments.hasMore, "a header with nothing loaded yet still draws, with Load more")
        assertEquals(listOf("OLD", "No group"), s.orgs[2].groups.map { it.group.title })
        assertFalse(s.orgs[2].groups[0].hasMore)
        assertEquals(8, s.total)

        val first = actions.treeCalls.single()
        assertEquals(WorkTreeFilters(), first.filters)
        assertEquals(MyWorkViewModel.PAGE, first.limit)
        assertEquals(0, first.perTask, "cards show counts, not occurrences")
    }

    /** Load more reads one section — its group, pinned to its org — then continues from that section's cursor. */
    @Test
    fun load_more_reads_one_section_and_then_follows_its_own_cursor() = runTest {
        val actions = FakeWorkActions()
        var sectionReads = 0
        actions.treeAnswer = { filters, cursor ->
            if (filters.group == null) {
                page(WorkTreeJson.TREE)
            } else {
                sectionReads += 1
                val base = page(WorkTreeJson.TREE_ABC_PAGE_TWO)
                // The first read of the section comes back from its top and says there is more.
                if (cursor == null) base.copy(tasks = page(WorkTreeJson.TREE).tasks.take(2), nextCursor = "s2") else base
            }
        }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.loadMore("1|tracker:1:ABC")
        runCurrent()
        val afterFirst = vm.state.value.orgs[0].groups[0]
        assertEquals(listOf("item:12", "item:13"), afterFirst.tasks.map { it.taskId }, "the section's top is not repeated")
        assertTrue(afterFirst.hasMore)

        vm.loadMore("1|tracker:1:ABC")
        runCurrent()

        val section = actions.treeCalls.drop(1)
        assertEquals(listOf(null, "s2"), section.map { it.cursor })
        assertTrue(section.all { it.filters.group == "tracker:1:ABC" && it.filters.org == IdOrWord.of(1) })
        val done = vm.state.value.orgs[0].groups[0]
        assertEquals(listOf("item:12", "item:13", "item:14"), done.tasks.map { it.taskId })
        assertFalse(done.hasMore)
        assertEquals(2, sectionReads)
    }

    /** The unassigned org's sections are read with `org: "none"`, never with no org at all. */
    @Test
    fun a_section_without_an_org_is_read_as_org_none() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.loadMore("none|none")
        runCurrent()

        assertEquals(IdOrWord.NONE, actions.treeCalls.last().filters.org)
        assertEquals("none", actions.treeCalls.last().filters.group)
    }

    /** Filters and folded sections outlive the screen: a new view model reads them back. */
    @Test
    fun filters_and_folded_sections_are_remembered() = runTest {
        val prefs = FakePrefs()
        val actions = FakeWorkActions().answeringTree()
        val first = myWork(actions = actions, prefs = prefs)
        first.attach()
        runCurrent()

        first.setStatus("open")
        first.toggleMine()
        first.toggleSection("org:1")
        runCurrent()

        assertEquals(WorkTreeFilters(status = "open", mine = true), actions.treeCalls.last().filters, "a change of filters re-reads with them")
        val again = myWork(actions = FakeWorkActions().answeringTree(), prefs = prefs)
        again.attach()
        runCurrent()
        assertEquals(WorkTreeFilters(status = "open", mine = true), again.state.value.filters)
        assertTrue(again.state.value.orgs[0].collapsed)
        assertFalse(again.state.value.orgs[1].collapsed)
    }

    /**
     * Archived tasks are hidden by default: the hub counts them
     * (`archived_hidden`) and the list's last row offers them. *Show
     * archived* re-reads with `archived: true`; it widens, so it is neither
     * counted nor a chip, and *Clear all* hides them again.
     */
    @Test
    fun archived_tasks_are_hidden_by_default_and_shown_on_demand() = runTest {
        val actions = FakeWorkActions()
        actions.treeAnswer = { filters, _ ->
            val p = page(WorkTreeJson.TREE)
            if (filters.archived == true) p.copy(archivedHidden = 0) else p.copy(archivedHidden = 4)
        }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        assertEquals(null, actions.treeCalls.single().filters.archived, "absent: the hub's default hides them")
        assertEquals(4, vm.state.value.archivedHidden)
        assertTrue(vm.state.value.archivedRow)
        assertFalse(vm.state.value.showArchived)

        vm.setArchived(true)
        runCurrent()
        assertEquals(true, actions.treeCalls.last().filters.archived)
        assertEquals(0, vm.state.value.archivedHidden)
        assertTrue(vm.state.value.archivedRow, "shown, the row stays to hide them again")
        assertEquals(0, vm.state.value.filters.sheetCount)
        assertEquals(emptyList(), vm.state.value.facets)

        vm.clearFilters()
        runCurrent()
        assertEquals(WorkTreeFilters(), vm.state.value.filters)
        assertEquals(4, vm.state.value.archivedHidden)
    }

    /** Sprint, epic and type come from the page's facets and go to the hub as the desktop sends them. */
    @Test
    fun a_sprint_an_epic_and_a_type_narrow_the_tree_and_name_their_chips() = runTest {
        val actions = FakeWorkActions().apply {
            treeAnswer = { _, _ ->
                page(
                    """{"tasks":[],"groups":[],"orgs":[],"trackers":[],"total":0,
                       "facets":{"iterations":[{"name":"Sprint 42","active":true,"count":3}],
                                 "epics":[{"task_id":"item:10","key":"PAY-10","title":"Checkout","count":2}],
                                 "item_types":["Bug"]}}""",
                )
            }
        }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()
        assertEquals(listOf("Sprint 42"), vm.state.value.planning.iterations.map { it.name })

        vm.setIteration("current")
        vm.setEpic("PAY-10")
        vm.setItemType("Bug")
        vm.setSort("key")
        runCurrent()
        val sent = actions.treeCalls.last().filters
        assertEquals(WorkTreeFilters(iteration = "current", epic = "PAY-10", itemType = "Bug", sort = "key"), sent)
        assertEquals(
            listOf("Current sprint", "Epic: PAY-10 Checkout", "Type: Bug"),
            vm.state.value.facets.map { it.label },
        )
        assertEquals(3, vm.state.value.filters.sheetCount, "sort orders, it does not narrow")
        assertEquals(
            """{"iteration":"current","epic":"PAY-10","item_type":"Bug","sort":"key"}""",
            json.encodeToString(WorkTreeFilters.serializer(), sent),
        )
    }

    /**
     * The box shows what was typed: the filters hold it trimmed, and showing
     * those ate the space after a word, so "login bug" could not be typed.
     */
    @Test
    fun a_space_typed_between_two_words_stays_in_the_box() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.setQuery("login")
        vm.setQuery("login ")
        runCurrent()
        assertEquals("login ", vm.state.value.queryText)
        assertEquals("login", vm.state.value.filters.query)
        vm.setQuery("login bug")
        advanceTimeBy(MyWorkViewModel.QUERY_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals("login bug", vm.state.value.queryText)
        assertEquals("login bug", actions.treeCalls.last().filters.query)

        vm.clearFilters()
        runCurrent()
        assertEquals("", vm.state.value.queryText)
    }

    /**
     * *Clear filters* clears the search too — it kept it, so on a tree
     * emptied by a search alone the empty state's button did nothing.
     */
    @Test
    fun clear_filters_clears_the_search_as_well() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.setQuery("nothing-matches")
        vm.setStatus("done")
        runCurrent()
        assertEquals(listOf("Status: Done", "Search: “nothing-matches”"), vm.state.value.facets.map { it.label })
        assertEquals(listOf("Status: Done"), vm.state.value.stripFacets.map { it.label }, "the search field shows its own")

        vm.clearFilters()
        advanceTimeBy(MyWorkViewModel.QUERY_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(WorkTreeFilters(), vm.state.value.filters)
        assertEquals(WorkTreeFilters(), actions.treeCalls.last().filters)
    }

    /** A chip names its org and tracker the way the page does, and its ✕ clears only that filter. */
    @Test
    fun a_chip_names_the_pages_org_and_clears_only_itself() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.setOrg(IdOrWord.of(1))
        vm.setTracker(IdOrWord.of(1))
        vm.toggleMine()
        runCurrent()
        assertEquals(listOf("Org: Acme", "Tracker: Jira (acme)", "Assigned to me"), vm.state.value.facets.map { it.label })
        assertEquals(2, vm.state.value.filters.sheetCount, "the toggle on screen is not counted on Filters")

        vm.clearFacet(dev.claudefleet.mobile.model.WorkFacetId.ORG)
        runCurrent()
        assertEquals(WorkTreeFilters(tracker = IdOrWord.of(1), mine = true), vm.state.value.filters)
        assertEquals(WorkTreeFilters(tracker = IdOrWord.of(1), mine = true), actions.treeCalls.last().filters)
    }

    /**
     * A remembered status this build does not offer is dropped on the way
     * in, rather than sent to the hub with no chip to show or clear it.
     */
    @Test
    fun a_stale_remembered_value_is_dropped() = runTest {
        val prefs = FakePrefs()
        prefs.putStringList(MyWorkViewModel.FILTERS_KEY, listOf("""{"status":"blocked","has":"later","org":"none"}"""))
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(actions = actions, prefs = prefs)
        vm.attach()
        runCurrent()

        assertEquals(WorkTreeFilters(org = IdOrWord.NONE), vm.state.value.filters)
        assertEquals(WorkTreeFilters(org = IdOrWord.NONE), actions.treeCalls.single().filters)
        assertEquals(listOf("Org: Unassigned"), vm.state.value.stripFacets.map { it.label })
    }

    /** Offline: the last page stays, says how old it is, and nothing can be written. */
    @Test
    fun offline_keeps_the_last_page_and_says_as_of_when() = runTest {
        val fleet = WorkFleet(caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = fleet, actions = actions)
        vm.attach()
        runCurrent()
        assertNull(vm.state.value.stale)
        assertTrue(vm.state.value.canSaveView)

        fleet.status.value = ConnectionStatus.Reconnecting(2, "the hub closed the stream")
        runCurrent()

        val s = vm.state.value
        // generated_at 1790000300 is 14:18:20 UTC; the test's device is on UTC.
        assertEquals("Offline · as of 14:18", s.stale)
        assertEquals(3, s.orgs.size, "the picture stays")
        assertFalse(s.canSaveView, "no write while not connected — and nothing queues")
        assertNull(vm.saveView("Mine"))
        runCurrent()
        assertEquals(OFFLINE_WRITE, vm.state.value.error, "a write tapped offline is refused out loud, not dropped")
        vm.loadMore("1|tracker:1:ABC")
        runCurrent()
        assertEquals(1, actions.treeCalls.size, "no read while offline either")
    }

    /**
     * A reconnect re-reads; a burst of `work:*` frames re-reads at once and
     * once more at the end of the throttle window — never once per frame,
     * and never held back for as long as frames keep coming.
     */
    @Test
    fun it_refetches_on_reconnect_and_throttles_a_burst_of_changes() = runTest {
        val fleet = WorkFleet(caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = fleet, actions = actions)
        vm.attach()
        runCurrent()
        assertEquals(1, actions.treeCalls.size)

        fleet.status.value = ConnectionStatus.Reconnecting(2, null)
        runCurrent()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        assertEquals(2, actions.treeCalls.size, "the ready after a drop reloads")

        fleet.workChanges.emit(1)
        fleet.workChanges.emit(2)
        fleet.workChanges.emit(3)
        runCurrent()
        assertEquals(3, actions.treeCalls.size, "the first change re-reads at once")
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
        assertEquals(4, actions.treeCalls.size, "the rest of the burst: one more, at the end of the window")

        // A frame every half window for five windows: a debounce would never
        // fire; the throttle re-reads once per window.
        repeat(10) {
            fleet.workChanges.emit(10L + it)
            advanceTimeBy(DEBOUNCE / 2)
            runCurrent()
        }
        assertTrue(actions.treeCalls.size in 8..10, "kept current under a steady stream: ${actions.treeCalls.size}")

        vm.detach()
        val before = actions.treeCalls.size
        fleet.workChanges.emit(99)
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
        assertEquals(before, actions.treeCalls.size, "not showing: not following")
    }

    /**
     * A session row re-reads the tree only when its work moved — its links
     * (`work_rev`), guess, org, or its being alive — never for the status and
     * activity churn of a working session.
     */
    @Test
    fun only_a_change_to_a_rows_work_rereads_not_its_status() = runTest {
        val row = SessionRow(id = 5, tmuxName = "dev", hostAlias = "pine", claudeStatus = "working", workRev = 7)
        val fleet = WorkFleet(rows = listOf(row), caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = fleet, actions = actions)
        vm.attach()
        runCurrent()
        assertEquals(1, actions.treeCalls.size)

        for (status in listOf("idle", "working", "blocked", "working")) {
            fleet.sessions.value = listOf(row.copy(claudeStatus = status, currentActivity = "step $status", contextPct = 40.0))
            fleet.sessionChanges.emit(5)
            advanceTimeBy(DEBOUNCE + 1)
            runCurrent()
        }
        assertEquals(1, actions.treeCalls.size, "status churn is not a work change")

        fleet.sessions.value = listOf(row.copy(workRev = 8))
        runCurrent()
        assertEquals(2, actions.treeCalls.size, "a secondary link moved (work_rev): re-read")

        fleet.sessions.value = listOf(row.copy(workRev = 8), row.copy(id = 6, workRev = 0))
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
        assertEquals(3, actions.treeCalls.size, "a session appeared")
    }

    /** A `work:changed` is never held behind session rows: each kind is throttled apart. */
    @Test
    fun a_work_frame_is_not_held_behind_session_churn() = runTest {
        val row = SessionRow(id = 5, tmuxName = "dev", hostAlias = "pine", workRev = 1)
        val fleet = WorkFleet(rows = listOf(row), caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = fleet, actions = actions)
        vm.attach()
        runCurrent()

        fleet.sessions.value = listOf(row.copy(workRev = 2))
        runCurrent()
        assertEquals(2, actions.treeCalls.size)
        fleet.workChanges.emit(1)
        runCurrent()
        assertEquals(3, actions.treeCalls.size, "read at once, not after the session window")
    }

    /**
     * Refresh vs Load more: a section page that answers after a refresh
     * replaced the list is dropped, never appended to a list it was not
     * read against.
     */
    @Test
    fun a_load_more_that_lands_after_a_refresh_is_dropped() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val hold = CompletableDeferred<Unit>()
        actions.treeGate = { call -> if (call.filters.group != null) hold.await() }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()
        val before = vm.state.value.orgs[0].groups[0].tasks.map { it.taskId }

        vm.loadMore("1|tracker:1:ABC")
        runCurrent()
        assertTrue(vm.state.value.orgs[0].groups[0].loadingMore)
        vm.refresh()
        runCurrent()
        hold.complete(Unit)
        runCurrent()

        val section = vm.state.value.orgs[0].groups[0]
        assertEquals(before, section.tasks.map { it.taskId }, "the stale page is not appended")
        assertFalse(section.loadingMore)
        vm.loadMore("1|tracker:1:ABC")
        runCurrent()
        assertEquals(listOf("item:12", "item:13", "item:14"), vm.state.value.orgs[0].groups[0].tasks.map { it.taskId }, "a fresh Load more still works")
    }

    /** The Work tab's badge follows the hub while the tab is not showing: on a work change and on a reconnect. */
    @Test
    fun the_badge_follows_work_changes_and_reconnects_while_detached() = runTest {
        val fleet = WorkFleet(caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().answeringTree().apply { reviewAnswer = ReviewPage(total = 2) }
        val vm = myWork(fleet = fleet, actions = actions)
        runCurrent()
        assertEquals(2, vm.state.value.reviewCount, "read on the first connect, tab or no tab")
        assertTrue(actions.treeCalls.isEmpty(), "the tree itself waits for the tab")

        actions.reviewAnswer = ReviewPage(total = 5)
        fleet.workChanges.emit(1)
        runCurrent()
        assertEquals(5, vm.state.value.reviewCount)

        actions.reviewAnswer = ReviewPage(total = 1)
        fleet.status.value = ConnectionStatus.Offline("stopped")
        runCurrent()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        assertEquals(1, vm.state.value.reviewCount, "a resume (a reconnect) re-reads it")
    }

    @Test
    fun a_readonly_token_is_offered_no_view_writes() = runTest {
        val actions = FakeWorkActions().answeringTree().apply { viewsAnswer = listOf(WorkView(1, "Mine", WorkTreeFilters(mine = true), 1)) }
        val vm = myWork(actions = actions, canWrite = false)
        vm.attach()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.loaded, "reading is fine")
        assertEquals(listOf("Mine"), s.views.map { it.name })
        assertFalse(s.canSaveView || s.canDeleteView)
        assertNull(vm.saveView("x"))
        assertNull(vm.deleteView(s.views.single()))
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("view_") })
    }

    /** A saved view applies its filters; saving expects no view yet (version 0), updating expects the one read. */
    @Test
    fun views_apply_their_filters_and_save_under_a_version() = runTest {
        val mine = WorkView(1, "Mine", WorkTreeFilters(mine = true, org = IdOrWord.of(1)), version = 3)
        val actions = FakeWorkActions().answeringTree().apply { viewsAnswer = listOf(mine) }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.applyView(mine)
        runCurrent()
        assertEquals(1L, vm.state.value.activeViewId)
        assertEquals(mine.filters, actions.treeCalls.last().filters)

        vm.saveView("  Open  ")
        runCurrent()
        vm.updateView(mine)
        runCurrent()
        assertEquals(listOf("view_save new Open v=0", "view_save 1 Mine v=3"), actions.calls)
    }

    /** Another device changed the view first: the hub's sentence, and Reload. */
    @Test
    fun a_conflicting_view_save_says_so_and_offers_reload() = runTest {
        val actions = FakeWorkActions().answeringTree().apply {
            failWrite = HubError.Tool("E_CONFLICT", "the view \"Mine\" changed: version 4")
        }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        vm.updateView(WorkView(1, "Mine", WorkTreeFilters(), 3))
        runCurrent()

        val s = vm.state.value
        assertTrue(s.conflict)
        assertEquals("Changed on another device", s.error?.title)
        assertEquals("the view \"Mine\" changed: version 4", s.error?.body)
        vm.reload()
        runCurrent()
        assertNull(vm.state.value.error, "a reload that answers clears it")
    }

    /**
     * A hub whose `work` takes a free-string action (before M8.0) may not
     * have the Work view at all: no tab to be refused, and nothing is read.
     */
    @Test
    fun a_hub_that_does_not_list_tree_in_its_enum_has_no_work_tab() = runTest {
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = WorkFleet(caps = WorkFleet.FULL), actions = actions)

        vm.attach()
        runCurrent()

        assertFalse(vm.state.value.available)
        assertTrue(actions.treeCalls.isEmpty())
    }

    /** No `tree` in the hub's enum: no tab, and nothing is read. */
    @Test
    fun a_hub_without_the_tree_action_has_no_work_tab() = runTest {
        val caps = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work" to setOf("tickets", "lookup"))))
        val actions = FakeWorkActions().answeringTree()
        val vm = myWork(fleet = WorkFleet(caps = caps), actions = actions)

        vm.attach()
        runCurrent()

        assertFalse(vm.state.value.available)
        assertTrue(actions.treeCalls.isEmpty())
    }

    /** A hub that lists `tree` but refuses it as unknown has it forgotten: the tab goes. */
    @Test
    fun an_unknown_tree_action_is_forgotten_for_the_connection() = runTest {
        val fleet = WorkFleet(caps = WorkFleet.WORK_VIEW)
        val actions = FakeWorkActions().apply { failTree = HubError.Tool("E_INVALID", "unknown work action \"tree\"; one of links, tickets") }
        val vm = myWork(fleet = fleet, actions = actions)

        vm.attach()
        runCurrent()

        assertFalse(fleet.capabilities.value.has("work", "tree"))
        assertFalse(vm.state.value.available)
    }

    /** The review count rides along with the tree, from `work { review }`'s total. */
    @Test
    fun the_review_count_is_the_inboxs_total() = runTest {
        val actions = FakeWorkActions().answeringTree().apply {
            reviewAnswer = json.decodeFromString(dev.claudefleet.mobile.model.ReviewPage.serializer(), WorkTreeJson.REVIEW)
        }
        val vm = myWork(actions = actions)
        vm.attach()
        runCurrent()

        assertTrue(vm.state.value.reviewAvailable)
        assertEquals(3, vm.state.value.reviewCount)
    }

    /**
     * The groups Place in group… offers: only `label:` groups a person or a
     * rule made — never the tracker's ABC or the key group OLD, which are
     * where a task sits by itself — and never "No group".
     */
    @Test
    fun known_groups_are_only_placements_and_rules() = runTest {
        val vm = myWork()
        vm.attach()
        runCurrent()

        assertEquals(listOf("Payments", "Ops"), vm.knownGroups().map { it.title })
        assertEquals(listOf("Payments", "Ops"), placeableGroups(vm.knownGroups()))
        assertEquals(
            emptyList(),
            placeableGroups(listOf(GroupRef("tracker:1:ABC", "ABC", GroupSource.Tracker), GroupRef("repo:acme/api", "acme/api", GroupSource.Repo), GroupRef("key:OLD", "OLD", GroupSource.Key))),
        )
    }

    @Test
    fun a_task_card_line_says_counts_and_trouble_in_words() {
        val tasks = page(WorkTreeJson.TREE).tasks
        assertEquals("In Review · Jira (acme) · 1 active · 2 past · 1 suggested · to review", taskCardLine(tasks[0]))
        assertEquals("no sessions · tracker down", taskCardLine(tasks[1]))
    }
}
