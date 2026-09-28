@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgTracker
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SessionTasks
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val LINKS: SessionTasks = json.decodeFromString(SessionTasks.serializer(), WorkTreeJson.SESSION_TASKS)

/** Two orgs: Acme owns tracker 1, Beta tracker 2. */
private val TWO_ORGS = OrgDirectory.of(
    listOf(
        OrgDetail(id = 1, name = "Acme", trackers = listOf(OrgTracker(1))),
        OrgDetail(id = 2, name = "Beta", trackers = listOf(OrgTracker(2))),
    ),
)

private fun TestScope.tasksVm(
    fleet: WorkFleet = WorkFleet(),
    actions: FakeWorkActions = FakeWorkActions().apply { sessionTasksAnswer = LINKS },
    canWrite: Boolean = true,
    opened: MutableList<String> = mutableListOf(),
) = SessionTasksViewModel(
    sessionId = 7,
    fleet = fleet,
    actions = actions,
    scope = backgroundScope,
    canWrite = canWrite,
    onOpenTask = { opened += it },
    refreshDebounceMs = 300,
)

class SessionTasksViewModelTest {

    @Test
    fun every_link_is_grouped_active_suggested_and_past() = runTest {
        val vm = tasksVm()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.available && s.loaded)
        assertEquals(listOf(42L, 44L), s.active.map { it.linkId }, "the primary first")
        assertEquals(listOf(45L), s.suggested.map { it.linkId })
        assertEquals(listOf(30L), s.past.map { it.linkId })
        assertEquals(42L, s.primaryLinkId)
        assertEquals(3, s.count, "what the chip counts: live and suggested, not past")
    }

    /** Make primary is a compare-and-set against the primary this screen read. */
    @Test
    fun make_primary_expects_the_current_primary() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(actions = actions)
        runCurrent()

        vm.makePrimary(vm.state.value.active[1])
        runCurrent()

        assertEquals(listOf("set_primary 7 44 expected=42"), actions.calls)
        assertEquals(2, actions.sessionTasksCalls, "re-read once the hub answered")
    }

    /**
     * Another device moved the primary first: the hub answers `E_CONFLICT`
     * naming the current one, nothing is shown as saved, and *Reload* reads
     * what is there now.
     */
    @Test
    fun make_primary_conflict_says_so_and_reload_reads_the_current_primary() = runTest {
        val actions = FakeWorkActions().apply {
            sessionTasksAnswer = LINKS
            failWrite = HubError.Tool("E_CONFLICT", "the primary is now OPS-1 (link 44)")
        }
        val vm = tasksVm(actions = actions)
        runCurrent()

        vm.makePrimary(vm.state.value.active[1])
        runCurrent()

        val s = vm.state.value
        assertTrue(s.conflict)
        assertEquals("Changed on another device", s.error?.title)
        assertEquals("the primary is now OPS-1 (link 44)", s.error?.body)
        assertEquals(42L, s.primaryLinkId, "never marked saved before the hub said so")
        assertFalse(s.busy)

        actions.sessionTasksAnswer = LINKS.copy(
            primaryLinkId = 44,
            links = LINKS.links.map { it.copy(primary = it.linkId == 44L) },
        )
        vm.reload()
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(44L, vm.state.value.primaryLinkId)
        assertEquals(listOf(44L, 42L), vm.state.value.active.map { it.linkId })
    }

    @Test
    fun remove_and_the_suggestion_decisions_carry_the_links_version() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(actions = actions)
        runCurrent()

        vm.remove(vm.state.value.active[1])
        runCurrent()
        vm.confirm(vm.state.value.suggested.single())
        runCurrent()
        vm.reject(vm.state.value.suggested.single())
        runCurrent()

        assertEquals(
            listOf("unlink 7 44 v=1", "confirm 7 45 primary=false v=2", "reject 7 45 v=2"),
            actions.workArgs,
            "a confirm never takes the primary from a session that has one",
        )
    }

    /** Add task… links as secondary when there is a primary; as the primary when there is none. */
    @Test
    fun adding_a_task_never_moves_an_existing_primary() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(actions = actions)
        runCurrent()

        vm.add(Ticket(id = 70, key = "PAY-7", title = "Refund"))
        runCurrent()
        actions.sessionTasksAnswer = SessionTasks(sessionId = 7)
        vm.reload()
        runCurrent()
        vm.setAddQuery("ops-9")
        vm.addTyped()
        runCurrent()

        assertEquals(listOf("link 7 70 primary=false", "link 7 ops-9 primary=true"), actions.workArgs)
    }

    /**
     * Before the session's links are read, whether it has a primary is
     * unknown — and an unknown never takes one: added and confirmed as
     * secondary, always saying so (the hub's default takes the primary).
     */
    @Test
    fun before_the_links_are_read_nothing_is_added_as_primary() = runTest {
        val actions = FakeWorkActions().apply { failSessionTasks = HubError.Tool("E_INTERNAL", "the hub is slow") }
        val vm = tasksVm(actions = actions)
        runCurrent()
        assertFalse(vm.state.value.loaded)

        vm.add(Ticket(id = 70, key = "PAY-7", title = "Refund"))
        runCurrent()
        vm.setAddQuery("OPS-9")
        vm.addTyped()
        runCurrent()
        vm.confirm(LINKS.links.first { it.state == dev.claudefleet.mobile.model.LinkState.Suggested })
        runCurrent()

        assertEquals(
            listOf("link 7 70 primary=false", "link 7 OPS-9 primary=false", "confirm 7 45 primary=false v=2"),
            actions.workArgs,
        )
        assertFalse(sessionHasNoPrimary(null), "not read is not \"none\"")
        assertTrue(sessionHasNoPrimary(SessionTasks(sessionId = 7)))
        assertFalse(sessionHasNoPrimary(LINKS.copy(primaryLinkId = null)), "a link marked primary counts though the id is missing")
    }

    /** The add list: the cache and My work / Recent, narrowed by the search, without what is already linked. */
    @Test
    fun the_add_list_is_searchable_and_leaves_out_linked_tasks() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply {
            sessionTasksAnswer = LINKS
            ticketsAnswer = mapOf(
                "mine" to listOf(Ticket(id = 12, key = "ABC-12", title = "Login fails"), Ticket(id = 70, key = "PAY-7", title = "Refund")),
                "recent" to listOf(Ticket(id = 71, key = "PAY-8", title = "Ledger")),
            )
        }
        val vm = tasksVm(fleet = fleet, actions = actions)
        runCurrent()

        vm.openAdd()
        runCurrent()
        assertEquals(listOf("PAY-7", "PAY-8"), vm.state.value.addCandidates.map { it.label }, "ABC-12 is already linked")
        vm.setAddQuery("ledg")
        runCurrent()
        assertEquals(listOf("PAY-8"), vm.state.value.addCandidates.map { it.label })
    }

    @Test
    fun a_readonly_token_sees_the_links_and_no_buttons() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(actions = actions, canWrite = false)
        runCurrent()

        val s = vm.state.value
        assertEquals(2, s.active.size)
        assertFalse(s.canMakePrimary || s.canRemove || s.canAdd || s.canConfirm || s.canReject)
        assertNull(vm.makePrimary(s.active[1]))
        assertNull(vm.remove(s.active[0]))
        assertNull(vm.add(Ticket(id = 1)))
        runCurrent()
        assertTrue(actions.calls.isEmpty())
    }

    @Test
    fun nothing_is_written_while_not_connected() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(fleet = fleet, actions = actions)
        runCurrent()

        fleet.status.value = ConnectionStatus.Reconnecting(2, null)
        runCurrent()

        assertFalse(vm.state.value.canMakePrimary)
        assertNull(vm.makePrimary(vm.state.value.active[1]))
        runCurrent()
        assertTrue(actions.calls.isEmpty())
        assertEquals(OFFLINE_WRITE, vm.state.value.error, "refused out loud, never queued")
    }

    /**
     * This session's *work* changing (its `work_rev`: a secondary link moved)
     * re-reads its links; its status churn does not, and neither does
     * another session's work.
     */
    @Test
    fun it_rereads_when_its_own_work_changes_only() = runTest {
        val seven = SessionRow(id = 7, tmuxName = "api", hostAlias = "mefistos", claudeStatus = "working", workRev = 3)
        val eight = SessionRow(id = 8, tmuxName = "web", hostAlias = "pine", workRev = 1)
        val fleet = WorkFleet(rows = listOf(seven, eight))
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        tasksVm(fleet = fleet, actions = actions)
        runCurrent()

        fleet.sessions.value = listOf(seven, eight.copy(workRev = 2))
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, actions.sessionTasksCalls, "another session's work")

        fleet.sessions.value = listOf(seven.copy(claudeStatus = "idle", currentActivity = "done"), eight.copy(workRev = 2))
        fleet.sessionChanges.emit(7)
        advanceTimeBy(301)
        runCurrent()
        assertEquals(1, actions.sessionTasksCalls, "its own status churn")

        fleet.sessions.value = listOf(seven.copy(claudeStatus = "idle", currentActivity = "done", workRev = 4), eight.copy(workRev = 2))
        runCurrent()
        assertEquals(2, actions.sessionTasksCalls, "its work_rev moved: at once")
    }

    @Test
    fun open_task_goes_to_the_links_task() = runTest {
        val opened = mutableListOf<String>()
        val vm = tasksVm(opened = opened)
        runCurrent()
        vm.openSheet()

        vm.openTask(vm.state.value.suggested.single())

        assertEquals(listOf("item:15"), opened)
        assertFalse(vm.state.value.sheetOpen)
    }

    /**
     * The add list keeps another org's tickets (so they are not "missing"),
     * after this org's and marked. Tapping one sends nothing: it opens the
     * choice — share it, or move the session with the command shown.
     */
    @Test
    fun another_orgs_ticket_opens_the_choice_and_sends_nothing() = runTest {
        val fleet = crossOrgFleet()
        val actions = FakeWorkActions().apply {
            sessionTasksAnswer = LINKS
            ticketsAnswer = mapOf(
                "mine" to listOf(
                    Ticket(id = 80, key = "PD-2592", title = "Support", trackerId = 1),
                    Ticket(id = 81, key = "OM-110", title = "API", trackerId = 2),
                    Ticket(id = 82, key = "LOC-1", title = "No tracker org"),
                ),
            )
        }
        val vm = tasksVm(fleet = fleet, actions = actions)
        runCurrent()
        vm.openAdd()
        runCurrent()

        val s = vm.state.value
        assertEquals(listOf("OM-110", "LOC-1", "PD-2592"), s.addCandidates.map { it.label }, "another org's last")
        assertEquals(mapOf(80L to "Acme"), s.otherOrg, "unassigned is never another org")
        assertEquals("Beta", s.sessionOrgName)

        assertNull(vm.add(s.addCandidates.last()))
        runCurrent()
        assertTrue(actions.workArgs.isEmpty(), "nothing sent")
        val choice = assertNotNull(vm.state.value.crossOrg)
        assertEquals("PD-2592", choice.what)
        assertEquals("Acme", choice.taskOrgName)
        assertEquals("Beta", choice.sessionOrgName)
        assertTrue(choice.canShare)
        assertEquals("fleet-hub org rule add 1 --owner beta-co --repo api", choice.moveCommand)
        assertEquals("every session in beta-co/api", choice.moveCovers)

        vm.dismissCrossOrg()
        runCurrent()
        assertNull(vm.state.value.crossOrg)
        vm.add(s.addCandidates.first())
        runCurrent()
        assertEquals(listOf("link 7 81 primary=false"), actions.workArgs)
    }

    /** **Link anyway** sends the same link again, forced — and only from the choice. */
    @Test
    fun link_anyway_shares_the_task_across_orgs() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(fleet = crossOrgFleet(), actions = actions)
        runCurrent()
        assertNull(vm.shareAcrossOrgs(), "nothing to share before a refusal")

        vm.add(Ticket(id = 80, key = "PD-2592", trackerId = 1))
        runCurrent()
        vm.shareAcrossOrgs()
        runCurrent()

        assertEquals(listOf("link 7 80 primary=false share"), actions.workArgs)
        assertNull(vm.state.value.crossOrg)
        assertNull(vm.state.value.error)
    }

    /**
     * A typed key the hub refuses across orgs (the phone could not know):
     * the choice opens from the refusal's details, and Link anyway sends the key.
     */
    @Test
    fun a_hub_cross_org_refusal_opens_the_choice() = runTest {
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(fleet = crossOrgFleet(), actions = actions)
        runCurrent()

        actions.fail = HubError.Tool(
            "E_FORBIDDEN",
            "work item 183 belongs to organisation 1 and the session to organisation 2; fleet does not link work " +
                "across organisations by mistake — pass force_cross_org: true if this is meant",
            json.parseToJsonElement("""{"work_org_id":1,"session_org_id":2,"cross_org":true}"""),
        )
        vm.setAddQuery("PD-2592")
        vm.addTyped()
        runCurrent()

        val choice = assertNotNull(vm.state.value.crossOrg)
        assertNull(vm.state.value.error, "a choice, not an error banner")
        assertEquals("PD-2592", choice.what)
        assertEquals("Acme", choice.taskOrgName)
        assertEquals("Beta", choice.sessionOrgName)

        actions.fail = null
        vm.shareAcrossOrgs()
        runCurrent()
        assertEquals(listOf("link 7 PD-2592 primary=false", "link 7 PD-2592 primary=false share"), actions.workArgs)
    }

    /** A hub whose `work_link` does not take the override: the choice says so and offers no button. */
    @Test
    fun without_the_override_the_choice_only_offers_the_move() = runTest {
        val fleet = crossOrgFleet(caps = WorkFleet.FULL)
        val actions = FakeWorkActions().apply { sessionTasksAnswer = LINKS }
        val vm = tasksVm(fleet = fleet, actions = actions)
        runCurrent()

        vm.add(Ticket(id = 80, key = "PD-2592", trackerId = 1))
        runCurrent()

        assertFalse(assertNotNull(vm.state.value.crossOrg).canShare)
        assertNull(vm.shareAcrossOrgs())
        runCurrent()
        assertTrue(actions.workArgs.isEmpty())
    }

    @Test
    fun the_move_command_falls_back_to_the_host_and_quotes_what_needs_it() {
        assertEquals(
            "fleet-hub org rule add 3 --host mefistos" to "every session on mefistos",
            moveSessionCommand(3, ProjectRow(id = 1, owner = "local", repo = "scratch"), "mefistos"),
        )
        assertEquals(
            "fleet-hub org rule add 3 --owner 'a b' --repo x" to "every session in a b/x",
            moveSessionCommand(3, ProjectRow(id = 1, owner = "a b", repo = "x"), null),
        )
        assertNull(moveSessionCommand(3, null, null))
    }
}

/** Session 7 on project beta-co/api in Beta; PD's tracker in Acme; a hub that takes the override. */
private fun crossOrgFleet(caps: HubCapabilities = SHARE_CAPS) = WorkFleet(
    rows = listOf(SessionRow(id = 7, tmuxName = "api", hostAlias = "mefistos", projectId = 5, orgId = 2)),
    caps = caps,
    projectRows = listOf(ProjectRow(id = 5, owner = "beta-co", repo = "api")),
).apply { orgs.value = TWO_ORGS }

private val SHARE_CAPS = HubCapabilities.of(
    ToolCatalog(setOf("work", "work_link"), params = mapOf("work_link" to setOf("force_cross_org"))),
)
