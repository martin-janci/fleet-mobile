@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.AccountUsageWindows as AccountLimits
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.UsageWindow as LimitWindow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_790_000_000L

private fun line(
    alias: String,
    reachable: Boolean = true,
    pinged: Long? = NOW - 60,
    claude: String? = "2.0.40",
    sessions: Int = 0,
    needsYou: Int = 0,
    working: Int = 0,
    hidden: Boolean = false,
) = HostLine(
    alias = alias,
    reachable = reachable,
    claudeVersion = claude,
    tmuxVersion = claude?.let { "3.4" },
    sessions = sessions,
    hidden = hidden,
    transport = "ssh",
    lastPingedAt = pinged,
    needsYou = needsYou,
    working = working,
)

private class ProbeFleet(hostRows: List<HostRow>, sessionRows: List<SessionRow> = emptyList(), caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(sessionRows)
    override val hosts = MutableStateFlow(hostRows)
    override val projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.9"))
    override val hubVersion = MutableStateFlow<String?>("0.9.9")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = emptyFlow<Long>()
    override val capabilities = MutableStateFlow(caps)
    var refreshes = 0
    override suspend fun refresh() {
        refreshes += 1
    }
}

private class ProbeOnly : HostActions {
    val probed = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun probe(alias: String): HostRow {
        probed += alias
        gate?.await()
        return HostRow(alias, reachable = true)
    }
    override suspend fun restorePlan(alias: String): RestoreReport = error("not on the Hosts list")
    override suspend fun restore(alias: String): RestoreReport = error("not on the Hosts list")
    override suspend fun discover(alias: String): List<LostCandidate> = error("not on the Hosts list")
    override suspend fun resume(alias: String, candidate: LostCandidate): SessionRow = error("not on the Hosts list")
}

private val PROBE = HubCapabilities.of(ToolCatalog(setOf("probe_host")))

/** More's places in the New layout (redesign 14.10, MobileMore): Hosts, Accounts and usage, Files. */
class MorePlacesTest {

    // ── Hosts ──

    @Test
    fun exceptions_come_first_lost_before_never_checked_and_healthy_rows_below() {
        val groups = hostGroups(
            listOf(
                line("mercury"),
                line("old-laptop", reachable = false, pinged = null, claude = null),
                line("oci-arm", reachable = false, pinged = NOW - 4 * 3600),
                line("nas", reachable = false, pinged = NOW - 600),
            ),
        )
        assertEquals(listOf("oci-arm", "nas", "old-laptop"), groups.attention.map { it.alias }, "longest gone first, never-checked last")
        assertEquals(listOf("mercury"), groups.connected.map { it.alias })
    }

    @Test
    fun the_headline_counts_lost_hosts_but_not_ones_never_checked() {
        val hosts = listOf(line("a"), line("b", reachable = false, pinged = NOW - 60), line("c", reachable = false, pinged = null, claude = null))
        assertEquals("3 hosts · 1 lost", hostsHeadline(hosts))
        assertEquals("1 host", hostsHeadline(listOf(line("a"))))
    }

    @Test
    fun a_lost_host_says_when_it_was_last_seen_and_what_waits_on_it() {
        val lost = line("oci-arm", reachable = false, pinged = NOW - 4 * 3600, sessions = 2)
        assertTrue(signalLost(lost))
        assertEquals("last seen 4 h ago · 2 sessions", hostRowLine(lost, NOW))
    }

    @Test
    fun a_healthy_row_says_what_its_sessions_are_doing_and_hidden_is_written() {
        assertEquals("5 sessions: 1 needs you · 3 working", hostRowLine(line("mercury", sessions = 5, needsYou = 1, working = 3), NOW))
        assertEquals("3 sessions", hostRowLine(line("hetzner-1", sessions = 3), NOW))
        assertEquals(
            "0 sessions · hidden from Sessions",
            hostRowLine(line("old-laptop", reachable = false, pinged = null, claude = null, hidden = true), NOW),
        )
    }

    @Test
    fun version_drift_is_measured_against_the_newest_claude_on_the_fleet() {
        val hosts = listOf(line("a", claude = "2.0.40"), line("b", claude = "2.0.31 (Claude Code)"), line("c", claude = null), line("d", claude = "dev"))
        assertEquals(setOf("b"), behindHosts(hosts), "unknown and unparseable versions are never behind")
        assertEquals(emptySet<String>(), behindHosts(listOf(line("a"), line("b"))))
    }

    @Test
    fun check_probes_one_host_marks_it_checking_and_relists() = runTest {
        val fleet = ProbeFleet(listOf(HostRow("oci-arm", reachable = false, lastPingedAt = NOW - 60)), caps = PROBE)
        val actions = ProbeOnly().apply { gate = CompletableDeferred() }
        val vm = HostsViewModel(fleet, backgroundScope, actions)
        runCurrent()

        vm.check("oci-arm")
        runCurrent()
        assertEquals(setOf("oci-arm"), vm.state.value.checking)
        assertNull(vm.check("oci-arm"), "a second tap while it runs is ignored")

        actions.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("oci-arm"), actions.probed)
        assertEquals(1, fleet.refreshes, "the re-list shows what the probe found")
        assertEquals(emptySet<String>(), vm.state.value.checking)
    }

    @Test
    fun check_is_not_offered_where_the_hub_has_no_probe() = runTest {
        val fleet = ProbeFleet(listOf(HostRow("oci-arm")), caps = HubCapabilities())
        val actions = ProbeOnly()
        assertNull(HostsViewModel(fleet, backgroundScope, actions).check("oci-arm"))
        assertNull(HostsViewModel(fleet, backgroundScope).check("oci-arm"), "the Classic screen passes no actions")
        assertTrue(actions.probed.isEmpty())
    }

    @Test
    fun a_hosts_line_counts_its_working_sessions() = runTest {
        val rows = listOf(1L, 2L).map {
            SessionRow(id = it, tmuxName = "s$it", hostAlias = "mercury", projectId = null, claudeStatus = "working", stuckKind = null, currentActivity = null, lastActivityAt = it)
        }
        val vm = HostsViewModel(ProbeFleet(listOf(HostRow("mercury", reachable = true)), rows, HubCapabilities()), backgroundScope)
        runCurrent()
        val mercury = vm.state.value.hosts.single()
        assertEquals(2, mercury.sessions)
        assertEquals(2, mercury.working)
    }

    // ── Files ──

    private fun file(state: FileState, host: String = "oci-arm", error: String? = null) = FileLine(
        id = 1,
        name = "tenant-trace.log",
        size = "412 KB",
        where = host,
        age = "4 h",
        state = state,
        error = error,
        host = host,
    )

    @Test
    fun a_failed_copy_off_a_host_that_is_gone_says_signal_lost() {
        val gone = { _: String -> false }
        val back = { _: String -> true }
        assertEquals("Signal lost", fileLead(file(FileState.Failed), transferring = false, hostReachable = gone))
        assertEquals("oci-arm is offline", fileLine(file(FileState.Failed), null, gone))
        assertEquals("Failed", fileLead(file(FileState.Failed, error = "permission denied"), transferring = false, hostReachable = back))
        assertEquals("permission denied", fileLine(file(FileState.Failed, error = "permission denied"), null, back))
    }

    @Test
    fun a_transfer_shows_its_real_size_not_a_spinner_word() {
        val transfer = Transfer(id = 1, name = "release.apk", received = 12_400_000, total = 31_000_000)
        assertNull(fileLead(file(FileState.Ready), transferring = true, hostReachable = { true }))
        assertEquals("12.4 of 31 MB · oci-arm", fileLine(file(FileState.Ready), transfer, { true }))
        assertEquals(0.4f, transfer.fraction!!, 0.001f)
    }

    @Test
    fun the_files_headline_counts_and_carries_the_hubs_budget() {
        val state = FilesUiState(loaded = true, files = listOf(file(FileState.Ready), file(FileState.Ready).copy(id = 2)), usage = "38 MB of 2 GB on the hub")
        assertEquals("2 files · 38 MB of 2 GB on the hub", filesHeadline(state))
        assertNull(filesHeadline(FilesUiState(loaded = false)))
    }

    // ── Accounts and usage ──

    @Test
    fun the_limits_note_says_where_they_are_instead_of_drawing_an_empty_meter() {
        assertTrue("desktop" in LIMITS_ON_DESKTOP && "not send them to phones" in LIMITS_ON_DESKTOP)
    }

    @Test
    fun an_accounts_limits_are_listed_in_the_boards_order_with_what_they_have() {
        val usage = AccountLimits(sevenDay = LimitWindow(40.0), fiveHour = LimitWindow(62.4), sevenDaySonnet = LimitWindow(10.0))
        assertEquals(listOf("5-hour", "Weekly", "Weekly Sonnet"), limitRows(usage).map { it.first })
    }

    @Test
    fun a_meter_says_how_much_is_used_and_when_it_starts_over() {
        val now = 1_000_000L
        assertEquals("62% used · resets in 2 h", limitFigure(LimitWindow(62.4, resetsAt = now + 2 * 3_600), now))
        assertTrue(nearLimit(LimitWindow(90.0), now))
        assertFalse(nearLimit(LimitWindow(89.9), now))
    }

    /** Review r05 M1: a reading from before its window's reset is not shown as current. */
    @Test
    fun a_window_past_its_reset_says_so_instead_of_its_old_figure() {
        val now = 1_000_000L
        val reset = LimitWindow(104.0, resetsAt = now - 60)
        assertEquals("Reset · not re-read yet", limitFigure(reset, now))
        assertFalse(nearLimit(reset, now), "a window that has started over is not near its limit")
        assertTrue(nearLimit(LimitWindow(100.0, resetsAt = now + 60), now))
    }

    @Test
    fun a_read_that_failed_says_why_and_how_old_the_meters_are() {
        val now = 1_000_000L
        val ok = AccountUsageSnapshot(accountUuid = "u1", usage = AccountLimits(), fetchedAt = now - 60, status = "ok")
        assertNull(limitsNote(ok, now))
        assertEquals("Not read yet.", limitsNote(null, now))
        assertEquals(
            "Its login expired; sign in again on the host · meters from 3 h ago.",
            limitsNote(ok.copy(status = "login_expired", fetchedAt = now - 3 * 3_600), now),
        )
        assertEquals("No host with this account is online.", limitsNote(ok.copy(status = "no_online_host", usage = null), now))
        assertEquals(
            "Refreshing its login · meters from 3 h ago.",
            limitsNote(ok.copy(status = "access_token_expired", fetchedAt = now - 3 * 3_600), now),
        )
        assertEquals("Could not read the limits · meters from 3 h ago.", limitsNote(ok.copy(status = "something_new", fetchedAt = now - 3 * 3_600), now))
    }

    @Test
    fun the_snapshot_reads_the_hubs_shape() {
        val body = """[{"account_uuid":"u1","usage":{"five_hour":{"utilization":62.5,"resets_at":1700000000},"seven_day":null,
            "seven_day_opus":null,"seven_day_sonnet":{"utilization":3.0,"resets_at":null}},"subscription":"max","fetched_at":1699990000,
            "source_host":"pine","status":"ok","detail":null,"next_try_at":1699990300}]"""
        val snap = dev.claudefleet.mobile.net.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(AccountUsageSnapshot.serializer()),
            body,
        ).single()
        assertEquals(62.5, snap.usage?.fiveHour?.utilization)
        assertNull(snap.usage?.sevenDay)
        assertEquals(1699990000L, snap.fetchedAt)
        assertEquals("ok", snap.status)
    }
}
