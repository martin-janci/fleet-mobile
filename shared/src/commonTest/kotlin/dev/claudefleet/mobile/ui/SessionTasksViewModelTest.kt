@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.SessionTaskLink
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.TaskBrief
import dev.claudefleet.mobile.model.TaskLink
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun link(id: Long, state: String, primary: Boolean = false, task: String) =
    SessionTaskLink(TaskLink(linkId = id, state = state, primary = primary, sessionId = 7), TaskBrief(taskId = task, key = task.substringAfter(':')))

class SessionTasksViewModelTest {

    private val every = SessionTasks(
        sessionId = 7,
        primaryLinkId = 42,
        links = listOf(
            link(39, "rejected", task = "item:10"),
            link(40, "ended", task = "item:50"),
            link(45, "suggested", task = "ref:OPS-2"),
            link(44, "active", task = "item:90"),
            link(42, "active", primary = true, task = "item:70"),
            link(38, "archived_by_a_newer_hub", task = "item:5"),
        ),
    )

    @Test
    fun every_link_is_listed_by_kind_primary_first() = runTest {
        val opened = mutableListOf<String>()
        val actions = FakeWorkViewActions().apply { sessionTasksAnswer = every }
        val tasks = SessionTasksViewModel(7, WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), actions, backgroundScope, onOpenTask = { opened += it })

        assertTrue(tasks.state.value.available)
        tasks.open()
        runCurrent()

        val groups = tasks.state.value.groups
        assertEquals(
            listOf(SessionTaskKind.Primary, SessionTaskKind.Secondary, SessionTaskKind.Suggested, SessionTaskKind.Past, SessionTaskKind.Rejected),
            groups.map { it.kind },
        )
        assertEquals(listOf("item:70"), groups[0].links.map { it.task.taskId })
        assertEquals(listOf("item:90"), groups[1].links.map { it.task.taskId })
        assertEquals(
            listOf("item:50", "item:5"),
            groups[3].links.map { it.task.taskId },
            "a state this build does not know is shown with the past, never as live",
        )
        assertEquals(6, tasks.state.value.count)
        assertEquals(listOf(7L), actions.sessionTaskCalls)

        tasks.openTask("item:90")
        runCurrent()
        assertEquals(listOf("item:90"), opened)
        assertFalse(tasks.state.value.open, "the sheet closes behind the task screen")
    }

    @Test
    fun an_older_hub_offers_no_tasks_section() = runTest {
        val older = HubCapabilities.of(ToolCatalog(setOf("work"), mapOf("work" to setOf("tickets", "tree"))))
        val actions = FakeWorkViewActions()
        val tasks = SessionTasksViewModel(7, WorkFleet(caps = older), actions, backgroundScope, onOpenTask = {})

        assertFalse(tasks.state.value.available)
        assertNull(tasks.open())
        runCurrent()
        assertEquals(emptyList(), actions.sessionTaskCalls)
    }

    /** A link change reaches every phone as its session's `session:updated`: the open sheet reads again. */
    @Test
    fun the_open_sheet_follows_its_sessions_changes() = runTest {
        val fleet = WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS)
        val actions = FakeWorkViewActions().apply { sessionTasksAnswer = every }
        val tasks = SessionTasksViewModel(7, fleet, actions, backgroundScope, onOpenTask = {})
        fleet.sessionChanges.emit(7)
        runCurrent()
        assertEquals(emptyList(), actions.sessionTaskCalls, "closed: nothing to redraw")

        tasks.open()
        runCurrent()
        fleet.sessionChanges.emit(8)
        runCurrent()
        fleet.sessionChanges.emit(7)
        runCurrent()
        assertEquals(listOf(7L, 7L), actions.sessionTaskCalls)
    }
}
