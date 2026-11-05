package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.UsageActions
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AccountUsageWindows
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.AutoSwitchView
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
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    override val accountUsage = MutableStateFlow(
        mapOf("u1" to AccountUsageSnapshot("u1", AccountUsageWindows(fiveHour = dev.claudefleet.mobile.model.UsageWindow(62.0)), "ok")),
    )
    override suspend fun refresh() = Unit
}

private class UsageCalls : UsageActions {
    val windows = mutableListOf<Long?>()
    var accountReads = 0
    var failReports = false
    override suspend fun report(sinceSecs: Long?): UsageReport {
        windows += sinceSecs
        if (failReports) throw IllegalStateException("report failed")
        return UsageReport(total = UsageTotals(costMicros = 1_230_000))
    }
    override suspend fun accounts(): List<AccountRow> {
        accountReads += 1
        return listOf(AccountRow(uuid = "u1", email = "a@b.c"))
    }
    val renames = mutableListOf<Pair<String, String?>>()
    override suspend fun setNickname(uuid: String, nickname: String?): AccountRow {
        renames += uuid to nickname
        return AccountRow(uuid = uuid, email = "a@b.c", nickname = nickname)
    }
    val forbids = mutableListOf<Pair<String, List<Long>>>()
    override suspend fun setForbiddenProjects(uuid: String, projectIds: List<Long>): AccountRow {
        forbids += uuid to projectIds
        return AccountRow(uuid = uuid, email = "a@b.c", forbiddenProjectIds = projectIds)
    }
    var auto = AutoSwitchView(enabled = true, continueAfter = false, fleetEnabled = true)
    val autoCalls = mutableListOf<Pair<Boolean?, Boolean?>>()
    override suspend fun autoSwitch(enabled: Boolean?, continueAfter: Boolean?): AutoSwitchView {
        autoCalls += enabled to continueAfter
        auto = auto.copy(enabled = enabled ?: auto.enabled, continueAfter = continueAfter ?: auto.continueAfter)
        return auto
    }
}

class UsageViewModelTest {

    private val both = setOf(HubCapabilities.USAGE_REPORT, HubCapabilities.LIST_ACCOUNTS)

    @Test
    fun opening_reads_the_week_and_the_accounts() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.attach()
        runCurrent()

        assertEquals(listOf<Long?>(UsageWindow.Week.seconds), calls.windows)
        assertEquals(1, calls.accountReads)
        assertEquals(1_230_000, vm.state.value.report?.total?.costMicros)
        assertEquals(listOf("a@b.c"), vm.state.value.accounts.map { it.label })
    }

    /** Review w23-9: a report that fails still leaves the accounts, and says why. */
    @Test
    fun a_failed_report_keeps_the_accounts() = runTest {
        val calls = object : UsageActions by UsageCalls() {
            override suspend fun report(sinceSecs: Long?): UsageReport = throw IllegalStateException("timed out")
        }
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertEquals(listOf("a@b.c"), vm.state.value.accounts.map { it.label })
        assertTrue(vm.state.value.error != null, "the failure is still the banner's")
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun another_window_rereads_the_report_but_not_the_accounts() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.attach()
        runCurrent()
        vm.select(UsageWindow.Day).join()
        vm.select(UsageWindow.Day).join()
        runCurrent()

        assertEquals(listOf<Long?>(UsageWindow.Week.seconds, UsageWindow.Day.seconds), calls.windows)
        assertEquals(1, calls.accountReads)
    }

    /** Review M6: the old window's totals never stand under the new chip. */
    @Test
    fun another_window_whose_read_fails_shows_no_old_totals() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertNotNull(vm.state.value.report)
        calls.failReports = true
        vm.select(UsageWindow.Day).join()
        runCurrent()
        assertNull(vm.state.value.report)
        assertTrue(vm.state.value.error != null)
    }

    @Test
    fun a_hub_without_the_tools_is_not_asked() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(emptySet()), calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertTrue(calls.windows.isEmpty())
        assertEquals(0, calls.accountReads)
        assertFalse(vm.state.value.limitsAvailable)
    }

    @Test
    fun each_account_shows_its_live_sessions_and_how_many_are_paused_on_it() = runTest {
        val calls = UsageCalls()
        val fleet = UsageFleet(both)
        fleet.sessions.value = listOf(
            SessionRow(id = 1, accountUuid = "u1", claudeStatus = "working"),
            SessionRow(id = 2, accountUuid = "u1", claudeStatus = "idle", attention = Attention("no_credentials")),
            SessionRow(id = 3, accountUuid = "u1", lostAt = 5),
            SessionRow(id = 4, accountUuid = "u1", status = "ghost"),
            SessionRow(id = 5, accountUuid = "u1", kind = "shell"),
            SessionRow(id = 6, accountUuid = "u2", attention = Attention("account_limit")),
            SessionRow(id = 7),
        )
        val vm = UsageViewModel(fleet, calls, backgroundScope)
        vm.attach()
        runCurrent()

        assertEquals(AccountSessions(live = 2, paused = 1), vm.state.value.sessions["u1"])
        assertEquals("2 sessions · 1 paused", accountSessionsLine(vm.state.value.sessions["u1"]))
        assertEquals("1 session · 1 paused", accountSessionsLine(vm.state.value.sessions["u2"]))
        assertNull(accountSessionsLine(vm.state.value.sessions["u3"]))

        // A session ending moves the count.
        fleet.sessions.value = fleet.sessions.value.filter { it.id != 1L }
        runCurrent()
        assertEquals("1 session · 1 paused", accountSessionsLine(vm.state.value.sessions["u1"]))
    }

    @Test
    fun a_hub_that_serves_the_limits_shows_the_fleets_readings() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both + HubCapabilities.ACCOUNT_USAGE), calls, backgroundScope)
        vm.attach()
        runCurrent()

        assertTrue(vm.state.value.limitsAvailable)
        assertEquals(62.0, vm.state.value.limits["u1"]?.usage?.fiveHour?.utilization)
    }

    @Test
    fun opening_before_discovery_reads_once_the_hub_says_it_serves_usage() = runTest {
        val calls = UsageCalls()
        val fleet = UsageFleet(emptySet())
        val vm = UsageViewModel(fleet, calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertTrue(calls.windows.isEmpty())
        assertEquals(0, calls.accountReads)

        // Discovery lands after the screen opened.
        fleet.capabilities.value = HubCapabilities(tools = both)
        runCurrent()
        assertEquals(listOf<Long?>(UsageWindow.Week.seconds), calls.windows)
        assertEquals(1, calls.accountReads)
        assertEquals(1_230_000, vm.state.value.report?.total?.costMicros)

        // A capabilities change that does not touch usage reads nothing more.
        fleet.capabilities.value = HubCapabilities(tools = both + HubCapabilities.ACCOUNT_USAGE)
        runCurrent()
        assertEquals(1, calls.windows.size)
    }

    @Test
    fun a_detached_screen_stops_following_the_capabilities() = runTest {
        val calls = UsageCalls()
        val fleet = UsageFleet(emptySet())
        val vm = UsageViewModel(fleet, calls, backgroundScope)
        vm.attach()
        runCurrent()
        vm.detach()
        fleet.capabilities.value = HubCapabilities(tools = both)
        runCurrent()
        assertTrue(calls.windows.isEmpty())
    }

    @Test
    fun renaming_an_account_takes_the_hubs_row() = runTest {
        val calls = UsageCalls()
        val vm = UsageViewModel(UsageFleet(both + HubCapabilities.SET_ACCOUNT_NICKNAME), calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertTrue(vm.state.value.canRename)

        vm.rename("u1", "  Work  ")
        runCurrent()
        assertEquals(listOf<Pair<String, String?>>("u1" to "Work"), calls.renames)
        assertEquals(listOf("Work"), vm.state.value.accounts.map { it.label })

        // Blank clears: the hub gets null and the email shows again.
        vm.rename("u1", " ")
        runCurrent()
        assertEquals("u1" to null, calls.renames.last())
        assertEquals(listOf("a@b.c"), vm.state.value.accounts.map { it.label })
    }

    // Keep an account off a project (claude-fleet migration 217).
    @Test
    fun forbidding_an_account_for_projects_takes_the_hubs_row() = runTest {
        val calls = UsageCalls()
        val fleet = UsageFleet(both + HubCapabilities.SET_ACCOUNT_PROJECTS)
        fleet.projects.value = listOf(ProjectRow(id = 3, owner = "acme", repo = "web"), ProjectRow(id = 1, owner = "acme", repo = "api"))
        val vm = UsageViewModel(fleet, calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertTrue(vm.state.value.canSetProjects)
        assertFalse(vm.state.value.canRename)
        assertEquals(2, vm.state.value.projects.size)

        vm.setForbiddenProjects("u1", listOf(3, 1, 3))
        runCurrent()
        assertEquals(listOf("u1" to listOf(1L, 3L)), calls.forbids)
        val row = vm.state.value.accounts.single()
        assertEquals(listOf(1L, 3L), row.forbiddenProjectIds)
        assertEquals("not for acme/api, acme/web", forbiddenProjectsLine(row, vm.state.value.projects))
        // A project the phone holds no row for still reads by its id.
        assertEquals("not for project #9", forbiddenProjectsLine(row.copy(forbiddenProjectIds = listOf(9)), emptyList()))
        assertNull(forbiddenProjectsLine(row.copy(forbiddenProjectIds = emptyList()), emptyList()))
    }

    @Test
    fun no_project_list_change_without_the_tool_or_from_a_readonly_pairing() = runTest {
        val calls = UsageCalls()
        val unlisted = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        val readonly = UsageViewModel(UsageFleet(both + HubCapabilities.SET_ACCOUNT_PROJECTS), calls, backgroundScope, canWrite = false)
        runCurrent()
        assertFalse(unlisted.state.value.canSetProjects)
        assertFalse(readonly.state.value.canSetProjects)
        unlisted.setForbiddenProjects("u1", listOf(1))
        readonly.setForbiddenProjects("u1", listOf(1))
        runCurrent()
        assertTrue(calls.forbids.isEmpty())
    }

    @Test
    fun no_rename_without_the_tool_or_from_a_readonly_pairing() = runTest {
        val calls = UsageCalls()
        val unlisted = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        val readonly = UsageViewModel(UsageFleet(both + HubCapabilities.SET_ACCOUNT_NICKNAME), calls, backgroundScope, canWrite = false)
        runCurrent()
        assertFalse(unlisted.state.value.canRename)
        assertFalse(readonly.state.value.canRename)
        unlisted.rename("u1", "Work")
        readonly.rename("u1", "Work")
        runCurrent()
        assertTrue(calls.renames.isEmpty())
    }

    @Test
    fun counts_are_compact() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2k", compactCount(1_234))
        assertEquals("2M", compactCount(2_000_000))
        assertEquals("3.5B", compactCount(3_456_000_000))
    }

    @Test
    fun automatic_switching_is_read_and_changed_where_the_hub_offers_it() = runTest {
        val calls = UsageCalls()
        val tools = both + HubCapabilities.AUTO_SWITCH_ACCOUNT
        val vm = UsageViewModel(UsageFleet(tools), calls, backgroundScope)
        vm.attach()
        runCurrent()
        assertEquals(true, vm.state.value.autoSwitch?.enabled)
        assertTrue(vm.state.value.canChangeAutoSwitch)
        assertFalse(vm.state.value.autoSwitchContinueAvailable, "no schema: an older hub")

        vm.setAutoSwitch(continueAfter = true)
        runCurrent()
        assertEquals(null to true, calls.autoCalls.last())
        assertEquals(true, vm.state.value.autoSwitch?.continueAfter)

        vm.setAutoSwitch(enabled = false)
        runCurrent()
        assertEquals(false, vm.state.value.autoSwitch?.enabled)
    }

    @Test
    fun no_automatic_switching_without_the_tool_or_from_a_readonly_pairing() = runTest {
        val calls = UsageCalls()
        val unlisted = UsageViewModel(UsageFleet(both), calls, backgroundScope)
        unlisted.attach()
        runCurrent()
        assertEquals(null, unlisted.state.value.autoSwitch)
        val readonly = UsageViewModel(UsageFleet(both + HubCapabilities.AUTO_SWITCH_ACCOUNT), calls, backgroundScope, canWrite = false)
        readonly.attach()
        runCurrent()
        assertFalse(readonly.state.value.canChangeAutoSwitch)
        val reads = calls.autoCalls.size
        readonly.setAutoSwitch(enabled = false)
        runCurrent()
        assertEquals(reads, calls.autoCalls.size)
    }
}
