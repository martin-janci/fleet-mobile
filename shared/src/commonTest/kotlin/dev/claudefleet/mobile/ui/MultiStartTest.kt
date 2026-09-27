@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.NewSessionActions
import dev.claudefleet.mobile.data.NewSessionRequest
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MultiStart
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgTracker
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.StartFailure
import dev.claudefleet.mobile.model.StartSkip
import dev.claudefleet.mobile.model.StartWarning
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

private val PINE = HostRow("pine", reachable = true)
private val PAY = ProjectRow(3, owner = "acme", repo = "pay", lastSessionAt = 300)
private val LEDGER = ProjectRow(4, owner = "acme", repo = "ledger", lastSessionAt = 200)
private val WEB = ProjectRow(5, owner = "globex", repo = "web", lastSessionAt = 100)

/** The hub after claude-fleet M9.6: `work_link` enumerates `start` and its schema lists `project_ids`. */
private val MULTI = HubCapabilities.of(
    ToolCatalog(
        names = setOf("work", "work_link"),
        actions = mapOf("work_link" to setOf("start", "resume", "link")),
        params = mapOf("work_link" to setOf("action", "key", "host_alias", "project_id", "project_ids")),
    ),
)

/** The same hub before M9.6: `start` is there, `project_ids` is not. */
private val SINGLE_ONLY = HubCapabilities.of(
    ToolCatalog(
        names = setOf("work", "work_link"),
        actions = mapOf("work_link" to setOf("start", "resume", "link")),
        params = mapOf("work_link" to setOf("action", "key", "host_alias", "project_id")),
    ),
)

private object NoCreate : NewSessionActions {
    override suspend fun newSession(request: NewSessionRequest): SessionRow = fail("a ticket start never calls new_session")
}

private fun started(id: Long, project: Long) = SessionRow(id = id, tmuxName = "pay-9-$id", hostAlias = "pine", projectId = project)

/**
 * Multi-start on the phone (work graph M13.4d, decision D15): ticked projects
 * beside the first, a confirm sheet with the count and the org, one
 * `work_link start { project_ids }`, and the answer shown per project. A
 * cross-org refusal is said in words and never retried with `force_cross_org`.
 */
class MultiStartTest {

    private fun fleet(caps: HubCapabilities = MULTI) =
        WorkFleet(caps = caps, hostRows = listOf(PINE), projectRows = listOf(PAY, LEDGER, WEB))

    private fun vm(
        fleet: WorkFleet,
        work: FakeWorkActions,
        scope: CoroutineScope,
        opened: MutableList<Long> = mutableListOf(),
        canWrite: Boolean = true,
    ) = NewSessionViewModel(
        fleet, NoCreate, scope, canWrite, initialHost = "pine", onCreated = { opened += it },
        ticketKey = "PAY-9", workActions = work,
    )

    /** A form with PAY picked first and LEDGER and WEB ticked, confirm sheet up. */
    private fun confirming(vm: NewSessionViewModel) {
        vm.selectProject(3)
        vm.toggleAlsoIn(4)
        vm.toggleAlsoIn(5)
        vm.create()
    }

    // ---- gating ----

    @Test
    fun offered_for_a_full_token_on_a_hub_that_takes_project_ids_once_a_project_is_picked() = runTest {
        val vm = vm(fleet(), FakeWorkActions(), backgroundScope)
        runCurrent()
        assertTrue(vm.state.value.canMultiStart)
        assertEquals(emptyList(), vm.state.value.alsoIn, "no first project yet: nothing to start beside")

        vm.selectProject(3)
        runCurrent()
        assertEquals(listOf(4L, 5L), vm.state.value.alsoIn.map { it.id }, "every other project, most recent first")
    }

    @Test
    fun never_offered_to_a_readonly_token_or_by_a_hub_without_project_ids() = runTest {
        val work = FakeWorkActions()
        val readonly = vm(fleet(), work, backgroundScope, canWrite = false)
        val older = vm(fleet(SINGLE_ONLY), work, backgroundScope)
        val bare = vm(fleet(WorkFleet.FULL), work, backgroundScope)
        runCurrent()

        for (v in listOf(readonly, older, bare)) {
            v.selectProject(3)
            v.toggleAlsoIn(4)
        }
        runCurrent()
        for (v in listOf(readonly, older, bare)) {
            assertFalse(v.state.value.canMultiStart)
            assertEquals(emptyList(), v.state.value.alsoIn)
            assertEquals(emptyList(), v.state.value.alsoInIds, "a tick the form cannot show does not count")
        }

        // The older hub still starts work — as a single start, one project.
        older.create()
        runCurrent()
        assertEquals(listOf("start PAY-9 pine 3"), work.calls)
    }

    @Test
    fun ticks_stop_at_the_hubs_cap_and_a_project_picked_first_drops_its_tick() = runTest {
        val many = (10L..20L).map { ProjectRow(it, owner = "acme", repo = "r$it", lastSessionAt = it) }
        val fleet = WorkFleet(caps = MULTI, hostRows = listOf(PINE), projectRows = listOf(PAY) + many)
        val vm = vm(fleet, FakeWorkActions(), backgroundScope)
        vm.selectProject(3)
        for (p in many) vm.toggleAlsoIn(p.id)
        runCurrent()
        assertEquals(ALSO_IN_MAX, vm.state.value.alsoInIds.size)
        assertEquals(MULTI_START_MAX, 8, "the hub's MULTI_START_MAX")

        vm.selectProject(10)
        runCurrent()
        assertFalse(10L in vm.state.value.alsoInIds, "the first project is not also a sibling")
    }

    // ---- the confirm sheet ----

    @Test
    fun ticking_and_tapping_start_opens_the_confirm_sheet_and_sends_nothing() = runTest {
        val work = FakeWorkActions()
        val fleet = fleet()
        fleet.tickets.value = listOf(Ticket(id = 90, key = "PAY-9", title = "Ledger", trackerId = 7))
        fleet.orgs.value = OrgDirectory.of(
            listOf(OrgDetail(1, "Acme", trackers = listOf(OrgTracker(7))), OrgDetail(2, "Globex")),
        )
        val vm = vm(fleet, work, backgroundScope)
        confirming(vm)
        runCurrent()

        val confirm = assertNotNull(vm.state.value.confirm)
        assertEquals(3, confirm.count)
        assertEquals(listOf("acme/pay", "acme/ledger", "globex/web"), confirm.projects, "the first-picked first, then in ticking order")
        assertEquals("Acme", confirm.orgLabel)
        assertEquals("Start PAY-9 in 3 projects on pine", multiStartTitle(confirm))
        assertEquals("Organisation: Acme", orgLine(confirm.orgLabel))
        assertEquals(emptyList(), work.calls, "nothing is sent before the sheet is confirmed")

        vm.cancelMultiStart()
        runCurrent()
        assertNull(vm.state.value.confirm)
        assertEquals(emptyList(), work.calls, "cancel sends nothing")
        assertEquals(listOf(4L, 5L), vm.state.value.alsoInIds, "and keeps the ticks")
    }

    @Test
    fun the_confirm_sheet_says_so_when_the_phone_does_not_know_the_org() = runTest {
        val vm = vm(fleet(), FakeWorkActions(), backgroundScope)
        confirming(vm)
        runCurrent()

        val confirm = assertNotNull(vm.state.value.confirm)
        assertNull(confirm.orgLabel)
        assertEquals("Organisation: not known on this phone", orgLine(confirm.orgLabel))
    }

    // ---- the request ----

    @Test
    fun confirming_sends_one_start_with_every_project_and_opens_the_first_when_all_started() = runTest {
        val work = FakeWorkActions().apply {
            manyAnswer = MultiStart(key = "PAY-9", started = listOf(started(61, 3), started(62, 4), started(63, 5)))
        }
        val opened = mutableListOf<Long>()
        val vm = vm(fleet(), work, backgroundScope, opened)
        confirming(vm)
        runCurrent()
        vm.confirmMultiStart()
        runCurrent()

        assertEquals(listOf("start_many PAY-9 pine [3, 4, 5]"), work.calls)
        assertEquals(listOf(61L), opened, "a clean start opens the first session, as a single start does")
        assertNull(vm.state.value.result)
        assertNull(vm.state.value.confirm)
        assertFalse(vm.state.value.creating)
    }

    @Test
    fun confirm_without_the_sheet_up_sends_nothing() = runTest {
        val work = FakeWorkActions()
        val vm = vm(fleet(), work, backgroundScope)
        vm.selectProject(3)
        vm.toggleAlsoIn(4)
        runCurrent()
        vm.confirmMultiStart()
        runCurrent()
        assertEquals(emptyList(), work.calls)
    }

    // ---- partial results ----

    @Test
    fun a_partial_start_is_shown_project_by_project_and_opens_nothing_by_itself() = runTest {
        val more = listOf(ProjectRow(6, "acme", "api"), ProjectRow(7, "acme", "infra"), ProjectRow(8, "acme", "docs"))
        val fleet = WorkFleet(caps = MULTI, hostRows = listOf(PINE), projectRows = listOf(PAY, LEDGER, WEB) + more)
        val work = FakeWorkActions().apply {
            manyAnswer = MultiStart(
                key = "PAY-9",
                started = listOf(started(61, 3), started(62, 6)),
                warnings = listOf(StartWarning(projectId = 6, sessionId = 62, code = "E_INTERNAL", message = "the link failed")),
                skipped = listOf(
                    StartSkip(projectId = 4, sessionId = 41, reason = "PAY-9 already runs in session 41"),
                    StartSkip(projectId = 8, reason = StartSkip.SKIP_DEADLINE),
                ),
                failed = listOf(
                    StartFailure(projectId = 5, code = "E_FORBIDDEN", message = "belongs to organisation 1 …", crossOrg = true),
                    StartFailure(projectId = 7, code = "E_NOTFOUND", message = "no checkout on pine"),
                ),
            )
        }
        val opened = mutableListOf<Long>()
        val vm = vm(fleet, work, backgroundScope, opened)
        vm.selectProject(3)
        for (id in listOf(4L, 5L, 6L, 7L, 8L)) vm.toggleAlsoIn(id)
        vm.create()
        runCurrent()
        vm.confirmMultiStart()
        runCurrent()

        val result = assertNotNull(vm.state.value.result)
        assertEquals(listOf(3L, 4L, 5L, 6L, 7L, 8L), result.projects.map { it.projectId }, "in the order asked")
        assertEquals(
            listOf(
                StartOutcome.STARTED,
                StartOutcome.ALREADY_RUNNING,
                StartOutcome.CROSS_ORG,
                StartOutcome.STARTED_WITH_WARNING,
                StartOutcome.REFUSED,
                StartOutcome.OUT_OF_TIME,
            ),
            result.projects.map { it.outcome },
        )
        assertEquals(listOf(61L, 41L, null, 62L, null, null), result.projects.map { it.sessionId }, "Open where there is a session")
        assertEquals("Started, but the link failed", result.projects[3].text)
        assertEquals("Not started: no checkout on pine", result.projects[4].text)
        assertEquals("acme/pay", result.projects[0].project)
        assertEquals(2, result.startedCount)
        assertEquals("PAY-9: started 2 of 6", resultTitle(result))
        assertEquals(emptyList(), opened, "leaving would hide what went wrong")

        vm.openStarted(62)
        runCurrent()
        assertEquals(listOf(62L), opened)
        assertNull(vm.state.value.result)
    }

    @Test
    fun a_project_the_answer_does_not_mention_says_so() {
        val r = multiStartResult("PAY-9", MultiStart(key = "PAY-9", started = listOf(started(61, 3))), listOf(3, 4), mapOf(3L to "acme/pay"), null)
        assertEquals(listOf(StartOutcome.STARTED, StartOutcome.REFUSED), r.projects.map { it.outcome })
        assertEquals("project #4", r.projects[1].project)
        assertTrue("did not say" in r.projects[1].text)
    }

    // ---- cross-org ----

    @Test
    fun a_cross_org_refusal_is_said_in_words_and_never_retried() = runTest {
        val work = FakeWorkActions().apply {
            manyAnswer = MultiStart(
                key = "PAY-9",
                started = listOf(started(61, 3)),
                failed = listOf(
                    StartFailure(
                        projectId = 5,
                        code = "E_FORBIDDEN",
                        message = "PAY-9 belongs to organisation 1 and the session to organisation 2; … pass force_cross_org: true if this is meant",
                        crossOrg = true,
                    ),
                ),
            )
        }
        val fleet = fleet()
        fleet.tickets.value = listOf(Ticket(id = 90, key = "PAY-9", trackerId = 7))
        fleet.orgs.value = OrgDirectory.of(listOf(OrgDetail(1, "Acme", trackers = listOf(OrgTracker(7)))))
        val vm = vm(fleet, work, backgroundScope)
        vm.selectProject(3)
        vm.toggleAlsoIn(5)
        vm.create()
        runCurrent()
        vm.confirmMultiStart()
        runCurrent()

        val line = assertNotNull(vm.state.value.result).projects.single { it.projectId == 5L }
        assertEquals(StartOutcome.CROSS_ORG, line.outcome)
        assertEquals(
            "Not started: this project belongs to a different organisation than PAY-9 (Acme). " +
                "The phone does not start work across organisations; do it from the desktop if it is meant.",
            line.text,
        )
        assertFalse("force_cross_org" in line.text, "the hub's hint to force it is not repeated")

        // Done, and anything else the sheet allows: still exactly one call.
        vm.dismissResult()
        runCurrent()
        assertEquals(listOf("start_many PAY-9 pine [3, 5]"), work.calls, "never a second start with force_cross_org")
    }

    /** A hub that refuses the whole call on the org rule gets the same words. */
    @Test
    fun a_whole_call_cross_org_refusal_is_said_in_words() = runTest {
        val work = FakeWorkActions().apply {
            fail = HubError.Tool(
                "E_FORBIDDEN",
                "PAY-9 belongs to organisation 1 and the session to organisation 2; pass force_cross_org: true if this is meant",
                buildJsonObject { put("work_org_id", 1); put("session_org_id", 2); put("cross_org", true) },
            )
        }
        val vm = vm(fleet(), work, backgroundScope)
        confirming(vm)
        runCurrent()
        vm.confirmMultiStart()
        runCurrent()

        val error = assertNotNull(vm.state.value.error)
        assertEquals("Another organisation", error.title)
        assertTrue("different organisation than PAY-9" in error.body, error.body)
        assertFalse("force_cross_org" in error.body)
        assertEquals(1, work.calls.size, "no retry")
        assertFalse(vm.state.value.creating)
        assertTrue(vm.state.value.canCreate, "the form unlocks; the person decides")
    }
}
