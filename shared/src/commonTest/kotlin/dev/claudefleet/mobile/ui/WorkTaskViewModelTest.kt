@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.LiveWork
import dev.claudefleet.mobile.model.ResumeMode
import dev.claudefleet.mobile.model.ResumePlan
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkTaskViewModelTest {

    private class Nav {
        val opened = mutableListOf<Long>()
        val started = mutableListOf<String>()
    }

    private fun vm(
        taskId: String,
        fleet: WorkFleet,
        view: FakeWorkViewActions,
        work: FakeWorkActions,
        scope: CoroutineScope,
        nav: Nav,
        canWrite: Boolean = true,
    ) = WorkTaskViewModel(
        taskId = taskId,
        fleet = fleet,
        viewActions = view,
        workActions = work,
        scope = scope,
        canWrite = canWrite,
        onOpenSession = { nav.opened += it },
        onStartHere = { nav.started += it },
        debounceMs = 1_000,
    )

    private val live7 = SessionRow(id = 7, tmuxName = "pay", hostAlias = "pine")
    private val live8 = SessionRow(id = 8, tmuxName = "pay-tests", hostAlias = "pine")

    private fun pastPlan() = ResumePlan(
        key = "PAY-9",
        hostAlias = "pine",
        modes = listOf(ResumeMode("last", ok = true)),
        hosts = listOf("pine", "hetzner"),
    )

    @Test
    fun it_lists_every_session_with_its_state_and_why() = runTest {
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_PAY7) }
        val task = vm("item:70", WorkFleet(listOf(live7, live8), WorkViewJson.WORK_VIEW_CAPS), view, FakeWorkActions(), backgroundScope, Nav())
        task.load()
        runCurrent()

        val s = task.state.value
        assertEquals(listOf("item:70"), view.taskCalls)
        assertEquals(listOf("active", "suggested", "ended"), s.sessions.map { it.link.state })
        assertEquals(listOf("branch pay-7-refund", "a prompt named PAY-7", "started from the ticket"), s.sessions.map { it.link.why })
        assertEquals(listOf(true, true, false), s.sessions.map { it.canOpen }, "an ended link has no session to open")
        assertEquals("pine · pay · primary", linkLine(s.sessions[0].link))
        assertEquals("hetzner · pay-old · ended", linkLine(s.sessions[2].link))
        assertEquals("Refunds must go out in 24 h.", s.detail?.description)
        assertEquals(7L, s.liveSessionId)
    }

    /** Something live on the task: Open it; Start here and Continue would make a second one. */
    @Test
    fun a_live_task_offers_open_and_neither_start_nor_continue() = runTest {
        val nav = Nav()
        val work = FakeWorkActions().apply { planAnswer = pastPlan() }
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_PAY7) }
        val task = vm("item:70", WorkFleet(listOf(live7), WorkViewJson.WORK_VIEW_CAPS), view, work, backgroundScope, nav)
        task.load()
        runCurrent()

        val s = task.state.value
        assertFalse(s.canStart)
        assertFalse(s.canContinue)
        assertEquals(emptyList(), work.calls, "no resume plan is read for a live task")
        task.open(7)
        task.open(41) // an ended link's id is not a session
        assertEquals(listOf(7L), nav.opened)
    }

    @Test
    fun a_task_with_no_session_offers_start_here_through_the_form() = runTest {
        val nav = Nav()
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val task = vm("item:90", WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), view, FakeWorkActions(), backgroundScope, nav)
        task.load()
        runCurrent()

        assertTrue(task.state.value.canStart)
        assertFalse(task.state.value.canContinue, "no past work")
        task.startHere()
        assertEquals(listOf("PAY-9"), nav.started)
    }

    /** Continue is `work_link resume` in the `last` mode, with the host picked — as the Tickets sheet does it. */
    @Test
    fun continue_resumes_the_last_conversation_on_the_chosen_host() = runTest {
        val nav = Nav()
        val work = FakeWorkActions().apply { planAnswer = pastPlan() }
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val task = vm("item:90", WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), view, work, backgroundScope, nav)
        task.load()
        runCurrent()

        val s = task.state.value
        assertTrue(s.canContinue)
        assertEquals(listOf("pine", "hetzner"), s.resumeHosts)
        assertEquals("pine", s.resumeHost)
        task.selectResumeHost("hetzner")
        task.continueWork()
        runCurrent()

        assertEquals(listOf("resume_plan PAY-9", "resume PAY-9 hetzner"), work.calls)
        assertEquals(listOf(99L), nav.opened)
    }

    /** The hub's `E_EXISTS` names a session already on it: jump there instead. */
    @Test
    fun continue_refused_as_existing_opens_that_session() = runTest {
        val nav = Nav()
        val work = FakeWorkActions().apply {
            planAnswer = pastPlan()
            fail = HubError.Tool("E_EXISTS", "PAY-9 is live on pine", buildJsonObject { put("session_id", 12) })
        }
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val task = vm("item:90", WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), view, work, backgroundScope, nav)
        task.load()
        runCurrent()
        task.continueWork()
        runCurrent()

        assertEquals(listOf(12L), nav.opened)
        assertNull(task.state.value.error)
    }

    /** A readonly pairing reads the task and is offered no Start and no Continue — and a racing tap calls nothing. */
    @Test
    fun a_readonly_token_is_offered_nothing_that_writes() = runTest {
        val nav = Nav()
        val work = FakeWorkActions().apply { planAnswer = pastPlan() }
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val task = vm("item:90", WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), view, work, backgroundScope, nav, canWrite = false)
        task.load()
        runCurrent()

        assertFalse(task.state.value.canStart || task.state.value.canContinue)
        task.startHere()
        task.continueWork()
        runCurrent()
        assertEquals(emptyList(), nav.started)
        assertEquals(listOf("resume_plan PAY-9"), work.calls, "the plan is a read; the resume is never sent")
    }

    /** A hub that does not list `start` / `resume` for this token hides them, as everywhere else. */
    @Test
    fun a_hub_without_start_or_resume_hides_them() = runTest {
        val caps = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work" to setOf("tree", "task", "resume_plan"), "work_link" to setOf("link"))))
        val work = FakeWorkActions().apply { planAnswer = pastPlan() }
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val task = vm("item:90", WorkFleet(caps = caps), view, work, backgroundScope, Nav())
        task.load()
        runCurrent()
        assertFalse(task.state.value.canStart || task.state.value.canContinue)
    }

    /** Out of scope answers exactly as unknown on the hub; the screen says so and draws nothing of it. */
    @Test
    fun a_task_the_hub_does_not_know_is_said_plainly() = runTest {
        val task = vm("item:404", WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS), FakeWorkViewActions(), FakeWorkActions(), backgroundScope, Nav())
        task.load()
        runCurrent()
        assertTrue(task.state.value.notFound)
        assertNull(task.state.value.error)
        assertNull(task.state.value.detail)
    }

    @Test
    fun a_change_to_one_of_its_sessions_re_reads_the_task() = runTest {
        val fleet = WorkFleet(listOf(live7), WorkViewJson.WORK_VIEW_CAPS)
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_PAY7) }
        val task = vm("item:70", fleet, view, FakeWorkActions(), backgroundScope, Nav())
        task.load()
        runCurrent()

        fleet.sessionChanges.emit(55)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(1, view.taskCalls.size, "not one of its sessions")
        fleet.sessionChanges.emit(8)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(2, view.taskCalls.size)
    }

    /** A plan that names a session live elsewhere still means Open — never a second session. */
    @Test
    fun a_session_linked_since_the_read_turns_start_into_open() = runTest {
        val view = FakeWorkViewActions().apply { taskAnswer = WorkViewJson.task(WorkViewJson.TASK_NO_SESSION) }
        val fleet = WorkFleet(caps = WorkViewJson.WORK_VIEW_CAPS)
        val work = FakeWorkActions().apply { planAnswer = pastPlan().copy(live = listOf(LiveWork(sessionId = 3))) }
        val task = vm("item:90", fleet, view, work, backgroundScope, Nav())
        task.load()
        runCurrent()
        assertTrue(task.state.value.canStart)

        fleet.sessions.value = listOf(
            SessionRow(id = 3, tmuxName = "ledger", work = dev.claudefleet.mobile.model.WorkSummary(linkId = 1, key = "PAY-9")),
        )
        runCurrent()
        assertEquals(3L, task.state.value.liveSessionId)
        assertFalse(task.state.value.canStart)
        assertFalse(task.state.value.canContinue)
    }
}
