@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
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
    fleet: WorkFleet = WorkFleet(),
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
        assertEquals(listOf("Acme", "Globex", "No organisation"), s.orgs.map { it.name })
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

    /** Offline: the last page stays, says how old it is, and nothing can be written. */
    @Test
    fun offline_keeps_the_last_page_and_says_as_of_when() = runTest {
        val fleet = WorkFleet()
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
        vm.loadMore("1|tracker:1:ABC")
        runCurrent()
        assertEquals(1, actions.treeCalls.size, "no read while offline either")
    }

    /** A reconnect re-reads; a burst of `work:*` and session frames re-reads once, after the debounce. */
    @Test
    fun it_refetches_on_reconnect_and_once_per_burst_of_changes() = runTest {
        val fleet = WorkFleet()
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
        fleet.sessionChanges.emit(5)
        fleet.workChanges.emit(2)
        runCurrent()
        assertEquals(2, actions.treeCalls.size, "debounced, not per frame")
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
        assertEquals(3, actions.treeCalls.size)

        vm.detach()
        fleet.workChanges.emit(3)
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
        assertEquals(3, actions.treeCalls.size, "not showing: not following")
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

    /** A free-string hub that refuses `tree` as unknown has it forgotten: the tab goes. */
    @Test
    fun an_unknown_tree_action_is_forgotten_for_the_connection() = runTest {
        val fleet = WorkFleet()
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

    /** The groups Place in group… offers: the tree's, by label, without "No group". */
    @Test
    fun known_groups_are_the_trees_labels() = runTest {
        val vm = myWork()
        vm.attach()
        runCurrent()

        assertEquals(listOf("ABC", "Payments", "Ops", "OLD"), vm.knownGroups().map { it.title })
    }

    @Test
    fun a_task_card_line_says_counts_and_trouble_in_words() {
        val tasks = page(WorkTreeJson.TREE).tasks
        assertEquals("In Review · Jira (acme) · 1 active · 2 past · 1 suggested · to review", taskCardLine(tasks[0]))
        assertEquals("no sessions · tracker down", taskCardLine(tasks[1]))
    }
}
