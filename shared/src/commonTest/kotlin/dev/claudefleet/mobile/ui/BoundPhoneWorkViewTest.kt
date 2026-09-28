@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.BatchResult
import dev.claudefleet.mobile.model.DecisionResult
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.ReviewKind
import dev.claudefleet.mobile.model.ReviewPage
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.WorkTreePage
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.store.FakePrefs
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BOUND_TREE: WorkTreePage = json.decodeFromString(WorkTreePage.serializer(), BoundPhoneJson.TREE)
private val BOUND_TREE_UNASSIGNED: WorkTreePage = json.decodeFromString(WorkTreePage.serializer(), BoundPhoneJson.TREE_WITH_UNASSIGNED)
private val BOUND_TASK: TaskDetail = json.decodeFromString(TaskDetail.serializer(), BoundPhoneJson.TASK)
private val BOUND_LINKS: SessionTasks = json.decodeFromString(SessionTasks.serializer(), BoundPhoneJson.SESSION_TASKS)
private val BOUND_REVIEW: ReviewPage = json.decodeFromString(ReviewPage.serializer(), BoundPhoneJson.REVIEW)
private val BOUND_ORGS: OrgDirectory =
    OrgDirectory.of(json.decodeFromString(ListSerializer(OrgDetail.serializer()), BoundPhoneJson.ORGS))

private const val THROTTLE = 500L

/** Session 7, org 1's, as the bound phone's `list_sessions` and session frames carry it. */
private val SEVEN = SessionRow(id = 7, tmuxName = "api", hostAlias = "mefistos", orgId = 1, workRev = 1)

/** A fleet as a bound phone sees it: its org's sessions and directory. It never emits [WorkFleet.workChanges]. */
private fun boundFleet(rows: List<SessionRow> = listOf(SEVEN)) =
    WorkFleet(rows = rows, caps = WorkFleet.WORK_VIEW).apply { orgs.value = BOUND_ORGS }

private fun TestScope.boundMyWork(fleet: WorkFleet, actions: FakeWorkActions) = MyWorkViewModel(
    fleet = fleet,
    actions = actions,
    scope = backgroundScope,
    canWrite = true,
    prefs = FakePrefs(),
    clock = { 1_790_000_999 },
    utcOffset = { 0 },
    refreshDebounceMs = THROTTLE,
)

private fun TestScope.boundTask(fleet: WorkFleet, actions: FakeWorkActions, taskId: String = "item:12") = TaskViewModel(
    taskId = taskId,
    fleet = fleet,
    actions = actions,
    scope = backgroundScope,
    canWrite = true,
    clock = { 1_790_000_000 },
    utcOffset = { 0 },
    refreshDebounceMs = THROTTLE,
)

private fun TestScope.boundSessionTasks(fleet: WorkFleet, actions: FakeWorkActions, sessionId: Long = 7) = SessionTasksViewModel(
    sessionId = sessionId,
    fleet = fleet,
    actions = actions,
    scope = backgroundScope,
    canWrite = true,
    refreshDebounceMs = THROTTLE,
)

/**
 * The Work view on a phone paired with `fleet-hub pair --org 1` (claude-fleet
 * M14.1b): the hub answers with org 1's rows only — and unassigned ones while
 * the org's D31 switch is on — and an out-of-scope id as `E_NOTFOUND`. The
 * phone shows what came back, invents and offers no other org, says a
 * refusal in words, and keeps current without the `work:changed` frames a
 * bound token is never sent.
 */
class BoundPhoneWorkViewTest {

    @Test
    fun my_work_shows_only_the_bound_orgs_sections_and_offers_no_other_org() = runTest {
        val actions = FakeWorkActions().apply { treeAnswer = { _, _ -> BOUND_TREE } }
        val vm = boundMyWork(boundFleet(), actions)

        vm.attach()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals(listOf("Acme"), s.orgs.map { it.name })
        assertEquals(listOf(1L), s.orgs.map { it.orgId })
        assertEquals(listOf("ABC", "Payments"), s.orgs.single().groups.map { it.group.title })
        assertEquals(listOf("item:12", "item:20"), s.orgs.single().groups.flatMap { it.tasks }.map { it.taskId })
        assertEquals(listOf(1L), s.filterOrgs.map { it.id }, "the Organisation filter offers the one org the hub listed")
        assertEquals(2, s.total)
        assertTrue(actions.calls.none { it.startsWith("assign_org") || it.startsWith("rule_") }, "${actions.calls}")
    }

    /** D31 on: the unassigned rows get their own section; still nothing of another org. */
    @Test
    fun with_unassigned_on_my_work_adds_the_unassigned_section_and_nothing_else() = runTest {
        val actions = FakeWorkActions().apply { treeAnswer = { _, _ -> BOUND_TREE_UNASSIGNED } }
        val vm = boundMyWork(boundFleet(), actions)

        vm.attach()
        runCurrent()

        val s = vm.state.value
        assertEquals(listOf("Acme", "Unassigned"), s.orgs.map { it.name })
        assertEquals(listOf(1L, null), s.orgs.map { it.orgId })
        assertEquals(listOf("ref:OLD-1"), s.orgs[1].groups.flatMap { it.tasks }.map { it.taskId })
        assertEquals(listOf(1L), s.filterOrgs.map { it.id })
    }

    /**
     * No `work:changed` ever arrives: My work still re-reads on a session row
     * whose work moved, on a reconnect, and on a pull — and shows what the
     * hub answers then (here: the org's D31 switch turned on meanwhile).
     */
    @Test
    fun my_work_keeps_current_without_work_changed() = runTest {
        val fleet = boundFleet()
        val actions = FakeWorkActions().apply { treeAnswer = { _, _ -> BOUND_TREE } }
        val vm = boundMyWork(fleet, actions)
        vm.attach()
        runCurrent()
        assertEquals(1, actions.treeCalls.size)

        actions.treeAnswer = { _, _ -> BOUND_TREE_UNASSIGNED }
        fleet.sessions.value = listOf(SEVEN.copy(workRev = 2))
        runCurrent()
        assertEquals(2, actions.treeCalls.size, "a session frame whose work moved re-reads at once")
        assertEquals(listOf("Acme", "Unassigned"), vm.state.value.orgs.map { it.name })

        advanceTimeBy(THROTTLE + 1)
        fleet.status.value = ConnectionStatus.Reconnecting(2, "the hub closed the stream")
        runCurrent()
        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        assertEquals(3, actions.treeCalls.size, "a reconnect re-reads")

        vm.refresh()
        runCurrent()
        assertEquals(4, actions.treeCalls.size, "a pull re-reads")
    }

    @Test
    fun task_shows_the_bound_orgs_task_under_its_name() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = BOUND_TASK }
        val vm = boundTask(boundFleet(), actions)
        runCurrent()

        val s = vm.state.value
        assertFalse(s.gone)
        assertEquals("Acme · from its tracker", s.orgLine)
        assertEquals(listOf(7L), s.active.mapNotNull { it.sessionId })
        assertTrue(s.suggested.isEmpty() && s.past.isEmpty(), "only the sessions the hub sent")
    }

    /** Another org's task answers as an unknown one: the screen says so, in words, and is not an error. */
    @Test
    fun an_out_of_scope_task_is_gone_and_said_in_words() = runTest {
        val actions = FakeWorkActions().apply { failTask = HubError.Tool("E_NOTFOUND", BoundPhoneJson.TASK_NOT_FOUND) }
        val vm = boundTask(boundFleet(), actions, taskId = "item:77")
        runCurrent()

        val s = vm.state.value
        assertTrue(s.gone)
        assertNull(s.error)
        assertNull(s.task, "nothing is drawn for it")
        assertTrue("this device may not see it" in TASK_NOT_VISIBLE, TASK_NOT_VISIBLE)
    }

    @Test
    fun task_keeps_current_without_work_changed() = runTest {
        val fleet = boundFleet()
        val actions = FakeWorkActions().apply { taskAnswer = BOUND_TASK }
        val vm = boundTask(fleet, actions)
        runCurrent()
        assertEquals(1, actions.taskCalls)

        fleet.sessions.value = listOf(SEVEN.copy(workRev = 2))
        runCurrent()
        assertEquals(2, actions.taskCalls, "a session frame whose work moved")

        vm.refresh()
        runCurrent()
        assertEquals(3, actions.taskCalls, "a pull")
    }

    @Test
    fun session_tasks_show_only_the_links_the_bound_hub_sent() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = BOUND_LINKS }
        val vm = boundSessionTasks(boundFleet(), actions)
        runCurrent()

        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals(listOf(42L), s.active.map { it.linkId })
        assertEquals(listOf(45L), s.suggested.map { it.linkId })
        assertTrue(s.past.isEmpty())
        assertNull(s.error)
    }

    /** Another org's session answers `E_NOTFOUND`: said as "Not found" with the hub's sentence. */
    @Test
    fun an_out_of_scope_session_is_refused_in_words() = runTest {
        val actions = FakeWorkActions().apply { failSessionTasks = HubError.Tool("E_NOTFOUND", BoundPhoneJson.SESSION_NOT_FOUND) }
        val vm = boundSessionTasks(boundFleet(), actions, sessionId = 9)
        runCurrent()

        val s = vm.state.value
        assertEquals("Not found", s.error?.title)
        assertEquals(BoundPhoneJson.SESSION_NOT_FOUND, s.error?.body)
        assertTrue(s.active.isEmpty() && s.suggested.isEmpty() && s.past.isEmpty())
    }

    @Test
    fun session_tasks_keep_current_without_work_changed() = runTest {
        val fleet = boundFleet()
        val actions = FakeWorkActions().apply { sessionTasksAnswer = BOUND_LINKS }
        boundSessionTasks(fleet, actions)
        runCurrent()
        assertEquals(1, actions.sessionTasksCalls)

        fleet.sessions.value = listOf(SEVEN.copy(workRev = 2))
        runCurrent()

        assertEquals(2, actions.sessionTasksCalls, "its own session frame, work_rev moved")
    }

    @Test
    fun review_shows_the_bound_orgs_cards_only() = runTest {
        val actions = FakeWorkActions().apply { reviewAnswer = BOUND_REVIEW }
        val vm = ReviewViewModel(boundFleet(), actions, backgroundScope, canWrite = true, clock = { 1_790_000_000 }, utcOffset = { 0 })
        vm.open()
        runCurrent()

        val s = vm.state.value
        assertEquals(1, s.total)
        assertEquals(listOf(ReviewKind.Suggestion), s.items.map { it.kind }, "no cross-org card")
        assertEquals(listOf(1L), s.items.map { it.task.orgId })

        vm.reload()
        runCurrent()
        assertEquals(2, actions.reviewCalls, "a pull re-reads")
    }

    /** A decision on a link that went out of scope meanwhile: refused on its card, in words. */
    @Test
    fun a_decision_the_bound_hub_refuses_is_said_on_its_card() = runTest {
        val actions = FakeWorkActions().apply {
            reviewAnswer = BOUND_REVIEW
            batchAnswer = BatchResult(listOf(DecisionResult(linkId = 45, ok = false, code = "E_NOTFOUND")))
        }
        val vm = ReviewViewModel(boundFleet(), actions, backgroundScope, canWrite = true, clock = { 1_790_000_000 }, utcOffset = { 0 })
        vm.open()
        runCurrent()

        vm.confirmAllShown()
        runCurrent()

        val failure = vm.state.value.failures[45L]
        assertEquals("E_NOTFOUND", failure?.code)
        assertEquals("It is no longer there.", failure?.message)
    }

    /** The Sessions list's org chips need two orgs; a bound phone's rows (its org and unassigned) have one. */
    @Test
    fun the_sessions_list_offers_no_org_chips_to_a_bound_phone() {
        val rows = listOf(SEVEN, SessionRow(id = 8, tmuxName = "scratch", hostAlias = "pine"))

        assertTrue(orgChoices(rows, BOUND_ORGS).isEmpty())
        assertEquals("Acme", BOUND_ORGS.name(1))
        assertEquals(setOf(1L), BOUND_ORGS.orgs.keys, "the directory holds what the hub listed, nothing more")
    }
}
