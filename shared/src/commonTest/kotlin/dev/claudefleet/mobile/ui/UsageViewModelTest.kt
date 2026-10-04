package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.UsageActions
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.UsageReport
import dev.claudefleet.mobile.model.UsageTotals
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class UsageFleet(tools: Set<String>) : FleetState {
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

private class UsageCalls : UsageActions {
    val windows = mutableListOf<Long?>()
    var accountReads = 0
    override suspend fun report(sinceSecs: Long?): UsageReport {
        windows += sinceSecs
        return UsageReport(total = UsageTotals(costMicros = 1_230_000))
    }
    override suspend fun accounts(): List<AccountRow> {
        accountReads += 1
        return listOf(AccountRow(uuid = "u1", email = "a@b.c"))
    }
}

class UsageViewModelTest {

    private val both = setOf(HubCapabilities.USAGE_REPORT, HubCapabilities.LIST_ACCOUNTS)

    @Test
    fun opening_reads_the_week_and_the_accounts() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.load().join()
        runCurrent()

        assertEquals(listOf<Long?>(UsageWindow.Week.seconds), calls.windows)
        assertEquals(1, calls.accountReads)
        assertEquals(1_230_000, vm.state.value.report?.total?.costMicros)
        assertEquals(listOf("a@b.c"), vm.state.value.accounts.map { it.label })
    }

    @Test
    fun another_window_rereads_the_report_but_not_the_accounts() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.load().join()
        vm.select(UsageWindow.Day).join()
        vm.select(UsageWindow.Day).join()
        runCurrent()

        assertEquals(listOf<Long?>(UsageWindow.Week.seconds, UsageWindow.Day.seconds), calls.windows)
        assertEquals(1, calls.accountReads)
    }

    @Test
    fun a_hub_without_the_tools_is_not_asked() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(emptySet()), calls, backgroundScope)
        vm.load().join()
        runCurrent()
        assertTrue(calls.windows.isEmpty())
        assertEquals(0, calls.accountReads)
    }

    @Test
    fun counts_are_compact() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2k", compactCount(1_234))
        assertEquals("2M", compactCount(2_000_000))
        assertEquals("3.5B", compactCount(3_456_000_000))
    }
}
