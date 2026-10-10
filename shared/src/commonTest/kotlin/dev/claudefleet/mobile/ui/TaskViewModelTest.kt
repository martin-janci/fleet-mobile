@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.GroupRef
import dev.claudefleet.mobile.model.GroupSource
import dev.claudefleet.mobile.model.LinkState
import dev.claudefleet.mobile.model.TaskDetail
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
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

private val DETAIL: TaskDetail = json.decodeFromString(TaskDetail.serializer(), WorkTreeJson.TASK)

/** The same task with its live and suggested sessions gone: only past work, one resumable. */
private val PAST_ONLY: TaskDetail = DETAIL.copy(
    task = DETAIL.task.copy(
        sessions = DETAIL.task.sessions.filter { it.state == LinkState.Ended || it.state == LinkState.Rejected },
        counts = DETAIL.task.counts.copy(active = 0, suggested = 0),
    ),
)

private class TaskNav {
    val opened = mutableListOf<Long>()
    val started = mutableListOf<String>()
    val tasks = mutableListOf<String>()
}

private fun TestScope.taskVm(
    fleet: WorkFleet = WorkFleet(),
    actions: FakeWorkActions = FakeWorkActions().apply { taskAnswer = DETAIL },
    canWrite: Boolean = true,
    nav: TaskNav = TaskNav(),
    scope: CoroutineScope = backgroundScope,
    callScope: CoroutineScope = scope,
) = TaskViewModel(
    taskId = "item:12",
    fleet = fleet,
    actions = actions,
    scope = scope,
    canWrite = canWrite,
    knownGroups = {
        listOf(
            GroupRef("label:Payments", "Payments", GroupSource.Rule),
            GroupRef("label:Ops", "Ops", GroupSource.Manual),
            // Derived: where a task sits by itself, never offered as a placement.
            GroupRef("tracker:1:ABC", "ABC", GroupSource.Tracker),
            GroupRef("repo:acme/api", "acme/api", GroupSource.Repo),
        )
    },
    onOpenSession = { nav.opened += it },
    onStartHere = { nav.started += it },
    onOpenTask = { nav.tasks += it },
    clock = { 1_790_000_000 },
    utcOffset = { 0 },
    refreshDebounceMs = 500,
    callScope = callScope,
)

class TaskViewModelTest {

    /** Every session, by state: active (primary first), suggested, and past — ended and rejected, never active. */
    @Test
    fun it_reads_the_task_and_sorts_its_sessions_by_state() = runTest {
        val vm = taskVm()
        runCurrent()

        val s = vm.state.value
        assertEquals(listOf(42L), s.active.map { it.linkId })
        assertEquals(listOf(43L), s.suggested.map { it.linkId })
        assertEquals(listOf(40L, 39L), s.past.map { it.linkId }, "ended newest first, then the rejected one")
        assertEquals("Org 1 · from its tracker", s.orgLine)
        assertEquals("ABC · from the tracker (ABC)", s.groupLine)
        assertTrue(s.trackerControlled, "the tracker's own group is labelled as such")
        assertEquals(listOf("Payments", "Ops"), s.knownGroups, "only a person's or a rule's groups — never the tracker's or a repository's")
        assertFalse(s.canContinue || s.canStart, "a live session is on it: Open, not a second one")
        assertTrue(s.canPlace)
    }

    /**
     * Place sends the placement's version, and the placement's note back
     * unchanged (the hub clears a note a `place` leaves out); it is shown
     * only after the hub answers, then re-read.
     */
    @Test
    fun placing_sends_the_placement_version_and_keeps_the_note() = runTest {
        val actions = FakeWorkActions().apply {
            taskAnswer = DETAIL
            placeAnswer = DETAIL.task.copy(group = GroupRef("label:Payments", "Payments", GroupSource.Manual), placementVersion = 3, sessions = emptyList())
        }
        val vm = taskVm(actions = actions)
        runCurrent()
        assertEquals("moved for the audit", vm.state.value.placementNote)
        vm.openPlace()

        vm.place(" Payments ")
        runCurrent()

        assertEquals(listOf("place item:12 \"Payments\" v=2 note=\"moved for the audit\""), actions.calls)
        assertEquals(2, actions.taskCalls, "the answer is followed by a re-read")
        assertFalse(vm.state.value.placeOpen)
    }

    /** An edited note is sent as edited; an emptied one is dropped on purpose; clearing the placement drops it too. */
    @Test
    fun an_edited_note_is_sent_and_clearing_drops_it() = runTest {
        val placed = DETAIL.copy(task = DETAIL.task.copy(group = GroupRef("label:Ops", "Ops", GroupSource.Manual)))
        val actions = FakeWorkActions().apply { taskAnswer = placed }
        val vm = taskVm(actions = actions)
        runCurrent()

        vm.place("Ops", note = " for the release ")
        runCurrent()
        vm.place("Ops", note = "")
        runCurrent()
        vm.clearPlacement()
        runCurrent()

        assertEquals(
            listOf("place item:12 \"Ops\" v=2 note=\"for the release\"", "place item:12 \"Ops\" v=2", "place item:12 \"\" v=2"),
            actions.calls,
        )
    }

    /**
     * A placement runs in the fleet's scope: backing out of the task while it
     * is on the wire does not cancel it, and nothing is re-read for a screen
     * that is gone.
     */
    @Test
    fun a_placement_outlives_the_screen_that_sent_it() = runTest {
        val gate = CompletableDeferred<Unit>()
        var answered = false
        val actions = FakeWorkActions().apply {
            taskAnswer = DETAIL
            writeGate = { gate.await(); answered = true }
        }
        val screen = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val vm = taskVm(actions = actions, scope = screen, callScope = backgroundScope)
        runCurrent()
        vm.place("Ops")
        runCurrent()

        screen.cancel()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, actions.calls.count { it.startsWith("place") }, "sent once")
        assertTrue(answered, "and answered, though the screen is gone")
        assertEquals(1, actions.taskCalls, "no re-read for a screen that no longer exists")
    }

    /** Another device placed it first: nothing is shown as saved, the sheet stays, and the hub's sentence says why. */
    @Test
    fun a_conflicting_placement_is_refused_and_says_so() = runTest {
        val actions = FakeWorkActions().apply {
            taskAnswer = DETAIL
            failWrite = HubError.Tool("E_CONFLICT", "placed in Ops on another device", buildJsonObject { put("version", 2) })
        }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.openPlace()

        vm.place("Payments")
        runCurrent()

        val s = vm.state.value
        assertTrue(s.conflict)
        assertEquals("Changed on another device", s.error?.title)
        assertEquals("placed in Ops on another device", s.error?.body)
        assertEquals("ABC", s.task?.group?.title, "the old group stays until a read says otherwise")
        assertTrue(s.placeOpen)
        vm.refresh()
        runCurrent()
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_placement_by_hand_can_be_cleared_back_to_what_is_derived() = runTest {
        val placed = DETAIL.copy(task = DETAIL.task.copy(group = GroupRef("label:Ops", "Ops", GroupSource.Manual), placementVersion = 2))
        val actions = FakeWorkActions().apply { taskAnswer = placed }
        val vm = taskVm(actions = actions)
        runCurrent()

        assertTrue(vm.state.value.canClearPlacement)
        vm.clearPlacement()
        runCurrent()

        assertEquals(listOf("place item:12 \"\" v=2"), actions.calls)
    }

    @Test
    fun a_readonly_token_and_a_dropped_connection_both_mean_no_edits() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = PAST_ONLY }
        val readonly = taskVm(actions = actions, canWrite = false)
        runCurrent()
        assertFalse(readonly.state.value.canPlace || readonly.state.value.canContinue || readonly.state.value.canStart)
        assertNull(readonly.place("Ops"))

        val fleet = WorkFleet()
        val offline = taskVm(fleet = fleet, actions = actions)
        runCurrent()
        assertTrue(offline.state.value.canPlace)
        fleet.status.value = ConnectionStatus.Offline("not connected")
        runCurrent()
        assertFalse(offline.state.value.canPlace || offline.state.value.canContinue)
        assertEquals("Offline · as of 14:13", offline.state.value.stale)
        assertNull(offline.place("Ops"))
        runCurrent()
        assertTrue(actions.calls.isEmpty())
        assertEquals(OFFLINE_WRITE, offline.state.value.error, "refused out loud, never queued")
    }

    /** Only past work: **Continue** resumes it and opens what the hub made; **Start here** opens the form. */
    @Test
    fun continue_resumes_the_key_and_start_here_opens_the_form() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = PAST_ONLY }
        val nav = TaskNav()
        val vm = taskVm(actions = actions, nav = nav)
        runCurrent()
        assertTrue(vm.state.value.canContinue && vm.state.value.canStart)

        vm.continueWork()
        runCurrent()
        vm.startHere()

        assertEquals(listOf("resume ABC-12 -"), actions.calls)
        assertEquals(listOf(99L), nav.opened)
        assertEquals(listOf("ABC-12"), nav.started)
    }

    /**
     * Continue needs an *ended* link that can resume: a rejected link (the
     * session was never on this task) does not enable it, nor does an ended
     * link whose session still lives (it holds the conversation), nor a
     * suggestion.
     */
    @Test
    fun only_an_ended_resumable_link_no_live_session_holds_enables_continue() = runTest {
        val ended = PAST_ONLY.task.sessions.first { it.state == LinkState.Ended }
        val rejected = PAST_ONLY.task.sessions.first { it.state == LinkState.Rejected }
        fun only(vararg links: dev.claudefleet.mobile.model.WorkTaskLink) =
            PAST_ONLY.copy(task = PAST_ONLY.task.copy(sessions = links.toList(), counts = PAST_ONLY.task.counts.copy(active = 0, suggested = 0)))

        val cases = listOf(
            only(rejected.copy(resumable = true, sessionId = 8)) to false,
            only(ended.copy(sessionId = 8)) to false,
            only(ended.copy(state = LinkState.Suggested, sessionId = 8, resumable = true)) to false,
            only(ended.copy(resumable = false)) to false,
            only(ended, rejected.copy(resumable = true)) to true,
        )
        for ((detail, expected) in cases) {
            val vm = taskVm(actions = FakeWorkActions().apply { taskAnswer = detail })
            runCurrent()
            assertEquals(expected, vm.state.value.canContinue, detail.task.sessions.joinToString { "${it.state}/${it.sessionId}/${it.resumable}" })
        }
        // A live, confirmed link to a live session: Open, never Continue.
        val live = taskVm()
        runCurrent()
        assertFalse(live.state.value.canContinue)
    }

    @Test
    fun continue_that_finds_a_live_session_jumps_to_it() = runTest {
        val actions = FakeWorkActions().apply {
            taskAnswer = PAST_ONLY
            fail = HubError.Tool("E_EXISTS", "ABC-12 is live", buildJsonObject { put("session_id", 41) })
        }
        val nav = TaskNav()
        val vm = taskVm(actions = actions, nav = nav)
        runCurrent()

        vm.continueWork()
        runCurrent()

        assertEquals(listOf(41L), nav.opened)
        assertNull(vm.state.value.error)
    }

    /** Open is for a live session; a past one has nothing to open. */
    @Test
    fun open_goes_to_a_live_session_only() = runTest {
        val nav = TaskNav()
        val vm = taskVm(nav = nav)
        runCurrent()

        vm.openSession(vm.state.value.active.single())
        vm.openSession(vm.state.value.past.first())

        assertEquals(listOf(7L), nav.opened)
    }

    @Test
    fun a_task_the_hub_does_not_show_is_gone_not_an_error() = runTest {
        val vm = taskVm(actions = FakeWorkActions())
        runCurrent()

        assertTrue(vm.state.value.gone)
        assertNull(vm.state.value.error)
    }

    @Test
    fun a_work_change_rereads_the_task_at_once_then_throttled() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply { taskAnswer = DETAIL }
        taskVm(fleet = fleet, actions = actions)
        runCurrent()
        assertEquals(1, actions.taskCalls)

        fleet.workChanges.emit(1)
        fleet.workChanges.emit(2)
        runCurrent()
        assertEquals(2, actions.taskCalls, "the first change re-reads at once")
        advanceTimeBy(501)
        runCurrent()

        assertEquals(3, actions.taskCalls, "the rest of the burst folds into one more")
    }

    @Test
    fun session_lines_say_state_and_why_in_words() {
        val s = DETAIL.task.sessions
        assertEquals("primary · set by hand", sessionLinkLine(s[0]))
        assertEquals("suggested · branch abc-12 · R3", sessionLinkLine(s[1]))
        assertEquals("ended · PR #9 · killed", sessionLinkLine(s[2]))
        assertEquals("not this", sessionLinkLine(s[3]))
        assertEquals("secondary", linkStateWords(s[0].copy(primary = false)))
        // A placement is local: its words never claim the tracker moved.
        assertEquals("placed by a person", groupSourceWords(WorkTask().group.copy(source = GroupSource.Manual)))
    }

    @Test
    fun a_past_session_is_summarised_and_the_summary_stays_until_dismissed() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = PAST_ONLY }
        val vm = taskVm(actions = actions)
        runCurrent()
        assertTrue(vm.state.value.canSummarize)
        val past = vm.state.value.past.first()

        vm.summarize(past)
        runCurrent()

        assertEquals("summarize ABC-12 ${past.linkId}", actions.calls.last())
        assertEquals("It fixed the refund rounding.", vm.state.value.summary?.summary)
        vm.dismissSummary()
        runCurrent()
        assertEquals(null, vm.state.value.summary)
    }

    /** New layout (14.9): each summary stays inline on its own card until Clear, and Regenerate replaces it. */
    @Test
    fun summaries_stay_on_their_card_until_cleared() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = PAST_ONLY }
        val vm = taskVm(actions = actions)
        runCurrent()
        val past = vm.state.value.past.first()

        vm.summarize(past)
        runCurrent()
        assertEquals("It fixed the refund rounding.", vm.state.value.summaries[past.linkId]?.summary)

        actions.summaryAnswer = actions.summaryAnswer.copy(summary = "Second draft.")
        vm.summarize(past)
        runCurrent()
        assertEquals("Second draft.", vm.state.value.summaries[past.linkId]?.summary)

        vm.clearSummary(past.linkId)
        runCurrent()
        assertNull(vm.state.value.summaries[past.linkId])
    }

    /**
     * A suggested session is answered on the task (14.9): Link confirms under
     * the link's version and never takes a primary from a session the phone
     * cannot see; Not this rejects; each re-reads the task.
     */
    @Test
    fun a_suggested_session_is_linked_or_turned_down_on_the_task() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = DETAIL }
        val vm = taskVm(actions = actions)
        runCurrent()
        assertTrue(vm.state.value.canDecide)
        val suggested = vm.state.value.suggested.single()
        val reads = actions.taskCalls

        vm.confirmLink(suggested)
        runCurrent()
        assertEquals("confirm ${suggested.sessionId} ${suggested.linkId} primary=false v=${suggested.linkVersion}", actions.workArgs.last())
        assertTrue(actions.taskCalls > reads, "the task is read again")

        vm.rejectLink(suggested)
        runCurrent()
        assertEquals("reject ${suggested.sessionId} ${suggested.linkId} v=${suggested.linkVersion}", actions.workArgs.last())
    }

    @Test
    fun linking_is_refused_offline_and_nothing_is_sent() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply { taskAnswer = DETAIL }
        val vm = taskVm(fleet = fleet, actions = actions)
        runCurrent()
        val suggested = vm.state.value.suggested.single()
        fleet.status.value = ConnectionStatus.Offline("gone")
        runCurrent()
        assertFalse(vm.state.value.canDecide)

        assertNull(vm.confirmLink(suggested))
        runCurrent()
        assertTrue(actions.workArgs.none { it.startsWith("confirm") })
        assertEquals(OFFLINE_WRITE, vm.state.value.error)
    }

    // ---- fleet's own task: its status and Edit (claude-fleet task editing) ----

    private val local: TaskDetail = DETAIL.copy(
        task = DETAIL.task.copy(
            kind = dev.claudefleet.mobile.model.TaskKind.Local,
            origin = "manual",
            dueAt = "2026-10-16",
            assignees = listOf("Ana"),
            statusCategory = dev.claudefleet.mobile.model.StatusCategory.Todo,
        ),
        notes = "old",
    )

    /** A ticket is its tracker's: no status chips, no Edit. Fleet's own task gets both. */
    @Test
    fun only_fleets_own_task_offers_status_and_edit() = runTest {
        val ticket = taskVm()
        runCurrent()
        assertFalse(ticket.state.value.canEdit || ticket.state.value.canSetStatus)

        val own = taskVm(actions = FakeWorkActions().apply { taskAnswer = local })
        runCurrent()
        val s = own.state.value
        assertTrue(s.canEdit && s.canSetStatus)
        assertEquals(TaskEditFields(title = "Login fails", notes = "old", assignees = listOf("Ana"), dueAt = "2026-10-16"), s.editFields)

        val readonly = taskVm(actions = FakeWorkActions().apply { taskAnswer = local }, canWrite = false)
        runCurrent()
        assertFalse(readonly.state.value.canEdit || readonly.state.value.canSetStatus)
    }

    /** Save sends only what changed, closes the sheet once the hub answered, and re-reads. */
    @Test
    fun saving_an_edit_sends_only_the_changed_fields() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = local }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.openEdit()
        runCurrent()
        assertTrue(vm.state.value.editOpen)

        val edit = taskEditOf("Login fails", "old", "Ana, Bo", "", vm.state.value.editFields, notesLocked = false)
        vm.saveEdit(edit)
        runCurrent()

        assertEquals(listOf("edit 12 title=null notes=null assignees=[Ana, Bo] due="), actions.calls)
        assertFalse(vm.state.value.editOpen)
        assertEquals(2, actions.taskCalls, "the answer is followed by a re-read")
    }

    /** A refused edit keeps the sheet open, with the hub's reason in it. */
    @Test
    fun a_refused_edit_stays_in_the_sheet() = runTest {
        val actions = FakeWorkActions().apply {
            taskAnswer = local
            failWrite = HubError.Tool("E_INVALID", "a due date is YYYY-MM-DD")
        }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.openEdit()
        vm.saveEdit(TaskEdit(dueAt = "soon"))
        runCurrent()
        assertTrue(vm.state.value.editOpen)
        assertTrue(vm.state.value.error != null)
    }

    /** A status chip sets fleet's own status; the one showing is not sent again. */
    @Test
    fun a_status_chip_sets_the_status() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = local }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.setStatus(dev.claudefleet.mobile.model.StatusCategory.Todo)
        vm.setStatus(dev.claudefleet.mobile.model.StatusCategory.Done)
        runCurrent()
        assertEquals(listOf("set_status 12 done"), actions.calls)
    }

    // ---- epics: epic → task → subtask (claude-fleet: local work three levels deep) ----

    private fun own(level: Int, parent: String? = null, epic: Boolean = false, total: Int = 0, done: Int = 0) = local.copy(
        task = local.task.copy(level = level, parentTaskId = parent, epic = epic, childrenTotal = total, childrenDone = done),
    )

    /** An epic says so, with its roll-up; a task under it names its parent, and a tap opens it. */
    @Test
    fun a_task_names_its_parent_and_an_epic_rolls_up_its_subtasks() = runTest {
        val epic = taskVm(actions = FakeWorkActions().apply { taskAnswer = own(level = 1, epic = true, total = 5, done = 2) })
        runCurrent()
        assertEquals("2 of 5 subtasks done", epic.state.value.subtaskRollup)
        assertNull(epic.state.value.parentTaskId)

        val parent = own(level = 1, epic = true).let { it.copy(task = it.task.copy(title = "Checkout revamp", key = null)) }
        val actions = FakeWorkActions().apply {
            taskAnswer = own(level = 2, parent = "item:7")
            taskAnswers = mapOf("item:7" to parent)
        }
        val nav = TaskNav()
        val child = taskVm(actions = actions, nav = nav)
        runCurrent()
        assertEquals(listOf("item:12", "item:7"), actions.taskIds, "the parent is read once, for its name")
        assertEquals("item:7", child.state.value.parentTaskId)
        assertEquals("Checkout revamp", child.state.value.parentLabel)
        child.openParent()
        assertEquals(listOf("item:7"), nav.tasks)
    }

    /** Three levels: an epic and a task take a subtask, a subtask does not; an older hub nests one level. */
    @Test
    fun add_subtask_is_offered_above_the_deepest_level_only() = runTest {
        fun offered(detail: TaskDetail, canWrite: Boolean = true): Boolean {
            val vm = taskVm(actions = FakeWorkActions().apply { taskAnswer = detail }, canWrite = canWrite)
            runCurrent()
            return vm.state.value.canAddSubtask
        }
        assertTrue(offered(own(level = 1)))
        assertTrue(offered(own(level = 2, parent = "item:7")))
        assertFalse(offered(own(level = 3, parent = "item:8")))
        // A hub that reports no level: a top-level item takes one, a child does not.
        assertTrue(offered(own(level = 0)))
        assertFalse(offered(own(level = 0, parent = "item:7")))
        // Never a ticket, never a readonly token.
        assertFalse(offered(DETAIL))
        assertFalse(offered(own(level = 1), canWrite = false))
    }

    /** Create files the subtask under this task, closes the sheet and re-reads, so the roll-up counts it. */
    @Test
    fun a_subtask_is_created_under_this_task() = runTest {
        val actions = FakeWorkActions().apply { taskAnswer = own(level = 1, epic = true) }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.openSubtask()
        runCurrent()
        assertTrue(vm.state.value.subtaskOpen)
        vm.addSubtask(" Pay by card ", "", "2026-10-30")
        runCurrent()
        assertEquals(listOf("create \"Pay by card\" parent=item:12 notes=null due=2026-10-30"), actions.calls)
        assertFalse(vm.state.value.subtaskOpen)
        assertEquals(2, actions.taskCalls, "the answer is followed by a re-read")
    }

    /** A refusal (past the deepest level, say) stays in the sheet with the hub's reason. */
    @Test
    fun a_refused_subtask_stays_in_the_sheet() = runTest {
        val actions = FakeWorkActions().apply {
            taskAnswer = own(level = 2, parent = "item:7")
            taskAnswers = mapOf("item:7" to own(level = 1))
            failWrite = HubError.Tool("E_INVALID", "local work nests three levels deep at most")
        }
        val vm = taskVm(actions = actions)
        runCurrent()
        vm.openSubtask()
        vm.addSubtask("Too deep", "", "")
        runCurrent()
        assertTrue(vm.state.value.subtaskOpen)
        assertTrue(vm.state.value.error != null)
    }
}
