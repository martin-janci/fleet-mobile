@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.PrFilter
import dev.claudefleet.mobile.model.PullRequest
import dev.claudefleet.mobile.model.PullRequestList
import dev.claudefleet.mobile.model.WorkTask
import dev.claudefleet.mobile.model.prChecksLabel
import dev.claudefleet.mobile.model.prRef
import dev.claudefleet.mobile.model.prStateLabel
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `prs { list }` as a hub on contract 12 answers it (claude-fleet `PullRequestRow`). */
private const val PRS_JSON = """
{"items":[
  {"id":2,"url":"https://github.com/acme/api/pull/118","repo":"acme/api","number":118,"title":"Fix login","head_ref":"abc-12-login",
   "state":"MERGED","draft":false,"ci_status":"passing","review_decision":"APPROVED","merged_at":1789996400,
   "session_id":7,"session_name":"abc-12 login","host_alias":"mefistos","project_id":3,"first_seen_at":1789990000,"updated_at":1789996400},
  {"id":1,"url":"https://github.com/acme/api/pull/101","state":"OPEN","draft":true,"first_seen_at":1789900000,"updated_at":1789900000,"something_new":true}
],"total":2}
"""

private val WITH_PRS = HubCapabilities.of(ToolCatalog(setOf("work", "prs")))

private fun TestScope.prsVm(
    actions: FakeWorkActions = FakeWorkActions().apply { prsAnswer = json.decodeFromString(PullRequestList.serializer(), PRS_JSON) },
    fleet: WorkFleet = WorkFleet(caps = WITH_PRS),
) = PullRequestsViewModel(fleet, actions, backgroundScope, clock = { 1_790_000_000 }, utcOffset = { 0 })

/**
 * Work's Pull requests on the phone (redesign 6.7): the desktop's list, read
 * through the hub's `prs { list }` (6.4, contract 12), offered only by a hub
 * whose `tools/list` names `prs`, and Blocked from 6.3 on the task rows.
 */
class PullRequestsViewModelTest {

    @Test
    fun a_row_decodes_with_what_the_hub_knows_and_ignores_what_it_added() {
        val page = json.decodeFromString(PullRequestList.serializer(), PRS_JSON)
        assertEquals(2, page.total)
        val merged = page.items[0]
        assertEquals("acme/api#118", prRef(merged))
        assertEquals("Merged", prStateLabel(merged))
        assertEquals("CI passing · Approved", prChecksLabel(merged))
        // An older `gh` answers only the basic fields: a bare row still reads.
        val bare = page.items[1]
        assertEquals("Draft", prStateLabel(bare))
        assertEquals("PR", prRef(bare))
        assertEquals("", prChecksLabel(bare))
        // A state this build has never heard of reads Open, never Merged.
        assertEquals("Open", prStateLabel(PullRequest(id = 3, state = "QUEUED")))
    }

    @Test
    fun a_rows_line_names_the_session_that_opened_it_or_says_it_is_gone() {
        val pr = json.decodeFromString(PullRequestList.serializer(), PRS_JSON).items[0]
        val now = 1_790_000_000L
        assertEquals(
            "acme/api#118 · abc-12-login · CI passing · Approved · merged 1 h ago · by abc-12 login · mefistos",
            prLine(pr, now, sessionLive = true),
        )
        assertEquals(
            "acme/api#118 · abc-12-login · CI passing · Approved · merged 1 h ago · by abc-12 login (gone)",
            prLine(pr, now, sessionLive = false),
        )
    }

    @Test
    fun it_opens_on_open_prs_and_rereads_for_another_filter() = runTest {
        val actions = FakeWorkActions().apply { prsAnswer = json.decodeFromString(PullRequestList.serializer(), PRS_JSON) }
        val vm = prsVm(actions = actions)
        assertTrue(vm.state.value.available)
        vm.open()
        runCurrent()
        assertTrue(vm.state.value.open && vm.state.value.loaded)
        assertEquals(PrFilter.OPEN, vm.state.value.filter)
        assertEquals(2, vm.state.value.items.size)

        vm.setFilter(PrFilter.MERGED)
        runCurrent()
        assertEquals(listOf("prs open", "prs merged"), actions.calls)
        // The same filter again reads nothing.
        assertNull(vm.setFilter(PrFilter.MERGED))
    }

    @Test
    fun a_hub_without_prs_offers_nothing_and_is_never_asked() = runTest {
        val actions = FakeWorkActions()
        val vm = prsVm(actions = actions, fleet = WorkFleet(caps = HubCapabilities.of(ToolCatalog(setOf("work")))))
        assertFalse(vm.state.value.available)
        assertNull(vm.open())
        runCurrent()
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun offline_the_last_rows_stay_with_their_age_and_nothing_is_read() = runTest {
        val actions = FakeWorkActions().apply { prsAnswer = json.decodeFromString(PullRequestList.serializer(), PRS_JSON) }
        val fleet = WorkFleet(caps = WITH_PRS)
        val vm = prsVm(actions = actions, fleet = fleet)
        vm.open()
        runCurrent()
        fleet.status.value = ConnectionStatus.Reconnecting(1, null)
        runCurrent()
        vm.reload()
        runCurrent()
        assertEquals(listOf("prs open"), actions.calls)
        assertEquals(2, vm.state.value.items.size)
        assertEquals("Offline · as of 14:13", vm.state.value.stale)
    }

    @Test
    fun a_failed_read_is_said_in_the_sheet() = runTest {
        val actions = FakeWorkActions().apply { fail = HubError.Tool("E_INTERNAL", "boom") }
        val vm = prsVm(actions = actions)
        vm.open()
        runCurrent()
        assertTrue(vm.state.value.error != null)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun a_task_decodes_blocked_and_an_older_hubs_task_is_not() {
        val t = json.decodeFromString(WorkTask.serializer(), """{"task_id":"item:3","blocked":true,"blocked_by":["item:7"]}""")
        assertTrue(t.blocked)
        assertEquals(listOf("item:7"), t.blockedBy)
        val old = json.decodeFromString(WorkTask.serializer(), """{"task_id":"item:3"}""")
        assertFalse(old.blocked)
        assertEquals(emptyList(), old.blockedBy)
    }
}
