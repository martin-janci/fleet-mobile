package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.CompanyActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class CompanyFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class CompanyCalls(var answer: List<OrgDetail>) : CompanyActions {
    var reads = 0
    var fail: Throwable? = null
    override suspend fun orgs(): List<OrgDetail> {
        reads += 1
        fail?.let { throw it }
        return answer
    }
}

class CompanyViewModelTest {

    private val work = setOf(HubCapabilities.WORK)

    @Test
    fun opening_reads_the_orgs_sorted_by_name() = runTest {
        val calls = CompanyCalls(listOf(OrgDetail(id = 2, name = "side"), OrgDetail(id = 1, name = "Acme")))
        val vm = CompanyViewModel(CompanyFleet(work), calls, backgroundScope)
        vm.load().join()
        runCurrent()

        assertEquals(1, calls.reads)
        assertTrue(vm.state.value.available)
        assertEquals(listOf("Acme", "side"), vm.state.value.orgs.map { it.name })
        assertNull(vm.state.value.open)
    }

    @Test
    fun a_hub_without_work_is_not_asked() = runTest {
        val calls = CompanyCalls(listOf(OrgDetail(id = 1, name = "Acme")))
        val vm = CompanyViewModel(CompanyFleet(emptySet()), calls, backgroundScope)
        vm.load().join()
        runCurrent()

        assertEquals(0, calls.reads)
        assertFalse(vm.state.value.available)
    }

    @Test
    fun an_org_opens_and_back_closes_it_before_leaving_the_screen() = runTest {
        val calls = CompanyCalls(listOf(OrgDetail(id = 1, name = "Acme")))
        val vm = CompanyViewModel(CompanyFleet(work), calls, backgroundScope)
        vm.load().join()
        vm.open(1)
        runCurrent()
        assertEquals("Acme", vm.state.value.open?.name)

        assertTrue(vm.close(), "the first back closes the org")
        runCurrent()
        assertNull(vm.state.value.open)
        assertFalse(vm.close(), "the second leaves the screen")
    }

    @Test
    fun an_org_that_left_the_list_closes_on_refresh() = runTest {
        val calls = CompanyCalls(listOf(OrgDetail(id = 1, name = "Acme"), OrgDetail(id = 2, name = "Side")))
        val vm = CompanyViewModel(CompanyFleet(work), calls, backgroundScope)
        vm.load().join()
        vm.open(2)
        calls.answer = listOf(OrgDetail(id = 1, name = "Acme"))
        vm.refresh().join()
        runCurrent()

        assertNull(vm.state.value.openId)
        assertEquals(listOf(1L), vm.state.value.orgs.map { it.id })
    }

    @Test
    fun a_failed_read_keeps_what_was_shown_and_says_so() = runTest {
        val calls = CompanyCalls(listOf(OrgDetail(id = 1, name = "Acme")))
        val vm = CompanyViewModel(CompanyFleet(work), calls, backgroundScope)
        vm.load().join()
        calls.fail = IllegalStateException("boom")
        vm.refresh().join()
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertEquals(listOf("Acme"), vm.state.value.orgs.map { it.name })
        assertFalse(vm.state.value.loading)
        vm.dismissError()
        runCurrent()
        assertNull(vm.state.value.error)
    }

    @Test
    fun spend_reads_against_its_budget_and_alone_without_one() {
        assertEquals("$12.40 of $50", spendAgainst(12_400_000, 50))
        assertEquals("$12.40", spendAgainst(12_400_000, 0))
        assertEquals("$12.40", spendAgainst(12_400_000, null))
        assertNull(spendAgainst(null, 50), "no spend sent is no figure, not \$0")
    }

    @Test
    fun an_org_line_names_its_sessions_role_and_a_budget_it_reached() {
        assertEquals("1 session", orgSummary(OrgDetail(id = 1, sessionCount = 1)))
        assertEquals(
            "3 sessions, 1 needs you · Admin · over its monthly budget",
            orgSummary(OrgDetail(id = 1, sessionCount = 3, needsYou = 1, myRole = "admin", overBudget = listOf("monthly"))),
        )
        assertEquals(
            "0 sessions · over its daily and monthly budget",
            orgSummary(OrgDetail(id = 1, overBudget = listOf("daily", "monthly"))),
        )
        assertEquals("Viewer", roleWord("viewer"))
    }
}
