@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AddHostActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.HostActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.model.OrgRule
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.RestoreOutcome
import dev.claudefleet.mobile.model.RestorePlanEntry
import dev.claudefleet.mobile.model.RestoreReport
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SshHost
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.ui.kit.StepState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Gap plan G5.8 (MobileMore, MobileInstall, MobileFullscreenLoaders,
 * MobileWizards): Run plan when back, ping ms, the repair loader, Install
 * agent from Add a host with tmux, host facts and the org on Add a project's
 * Where step.
 */

private class G58Fleet(hosts: List<HostRow>, tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(hosts)
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Restores : HostActions {
    val calls = mutableListOf<String>()
    var fail: Throwable? = null
    override suspend fun probe(alias: String) = HostRow(alias = alias, reachable = true).also { calls += "probe" }
    override suspend fun restorePlan(alias: String): RestoreReport {
        calls += "plan"
        return RestoreReport(plan = listOf(RestorePlanEntry(sessionId = 5, tmuxName = "lost-one", action = "restore")))
    }
    override suspend fun restore(alias: String): RestoreReport {
        calls += "restore"
        fail?.let { throw it }
        return RestoreReport(
            dryRun = false,
            results = listOf(RestoreOutcome(sessionId = 5, tmuxName = "lost-one", ok = true), RestoreOutcome(sessionId = 6, tmuxName = "x", ok = false)),
        )
    }
    override suspend fun discover(alias: String): List<LostCandidate> = emptyList<LostCandidate>().also { calls += "discover" }
    override suspend fun resume(alias: String, candidate: LostCandidate) = SessionRow(id = 1, tmuxName = "r")
}

private val RESTORE_TOOLS = setOf(HubCapabilities.PROBE_HOST, HubCapabilities.RESTORE_HOST_SESSIONS, HubCapabilities.DISCOVER_LOST_SESSIONS)

private class AddsHosts(private val tmux: String?) : AddHostActions {
    override suspend fun candidates() = listOf(SshHost(alias = "pine.lan", hostname = "10.0.0.5"), SshHost(alias = "oak"))
    override suspend fun add(alias: String, sshAlias: String) = HostRow(alias = alias, sshAlias = sshAlias, reachable = true, tmuxVersion = tmux)
}

class HostsInstallWizardsTest {

    // ── Run plan when back ──

    @Test
    fun run_plan_when_back_restores_once_the_host_answers_again() = runTest {
        val fleet = G58Fleet(listOf(HostRow(alias = "pine", reachable = false, lastPingedAt = 1)), RESTORE_TOOLS)
        val actions = Restores()
        val vm = HostDetailViewModel(fleet, actions, backgroundScope, canWrite = true)
        vm.open("pine").join()
        runCurrent()
        assertTrue(vm.state.value.canRunWhenBack)

        vm.runWhenBack()
        runCurrent()
        assertTrue(vm.state.value.whenBack)
        assertFalse("restore" in actions.calls, "nothing runs while the host is gone")

        // The sheet can close; the arm stays with the phone.
        vm.close()
        fleet.hosts.value = listOf(HostRow(alias = "pine", reachable = true, lastPingedAt = 2))
        runCurrent()
        assertEquals(1, actions.calls.count { it == "restore" })

        // A second re-list does not run it again.
        fleet.hosts.value = listOf(HostRow(alias = "pine", reachable = true, lastPingedAt = 3))
        runCurrent()
        assertEquals(1, actions.calls.count { it == "restore" })

        vm.open("pine").join()
        runCurrent()
        assertFalse(vm.state.value.whenBack)
        assertEquals("pine answered again: restored 1 of 2.", vm.state.value.backNote)
        assertFalse(vm.state.value.canRunWhenBack, "a host that answers restores now, not when back")
    }

    @Test
    fun a_disarmed_or_readonly_host_never_restores_when_back() = runTest {
        val fleet = G58Fleet(listOf(HostRow(alias = "pine", reachable = false, lastPingedAt = 1)), RESTORE_TOOLS)
        val actions = Restores()
        val vm = HostDetailViewModel(fleet, actions, backgroundScope, canWrite = true)
        vm.open("pine").join()
        vm.runWhenBack()
        vm.cancelWhenBack()
        fleet.hosts.value = listOf(HostRow(alias = "pine", reachable = true))
        runCurrent()
        assertFalse("restore" in actions.calls)

        val readonly = HostDetailViewModel(G58Fleet(listOf(HostRow(alias = "pine")), RESTORE_TOOLS), Restores(), backgroundScope, canWrite = false)
        readonly.open("pine").join()
        runCurrent()
        assertFalse(readonly.state.value.canRunWhenBack)
        readonly.runWhenBack()
        runCurrent()
        assertFalse(readonly.state.value.whenBack)
    }

    @Test
    fun a_failed_restore_when_back_says_so() = runTest {
        val fleet = G58Fleet(listOf(HostRow(alias = "pine", reachable = false, lastPingedAt = 1)), RESTORE_TOOLS)
        val actions = Restores().apply { fail = HubError.Transport(IllegalStateException("gone")) }
        val vm = HostDetailViewModel(fleet, actions, backgroundScope, canWrite = true)
        vm.open("pine").join()
        vm.runWhenBack()
        fleet.hosts.value = listOf(HostRow(alias = "pine", reachable = true))
        runCurrent()
        assertTrue(vm.state.value.backNote!!.startsWith("pine answered again, but the restore failed"))
        assertEquals("pine answered again; there was nothing to restore.", backNote("pine", RestoreReport()))
    }

    // ── Ping and host facts ──

    @Test
    fun host_rows_show_the_hubs_ping_only_for_a_host_that_answers() = runTest {
        val rows = json.decodeFromString(
            ListSerializer(HostRow.serializer()),
            """[{"alias":"pine","reachable":true,"latency_ms":42,"disk_home_free_kb":222000000,"cpu_count":16,"mem_total_kb":65000000},
               {"alias":"local","reachable":true,"latency_ms":null},
               {"alias":"elm","reachable":false,"last_pinged_at":1,"latency_ms":80}]""",
        )
        val fleet = G58Fleet(rows, emptySet())
        val vm = HostsViewModel(fleet, backgroundScope)
        runCurrent()
        val lines = vm.state.value.hosts.associateBy { it.alias }
        assertEquals("0 sessions · ping 42 ms", hostRowLine(lines.getValue("pine"), 100))
        assertEquals("0 sessions", hostRowLine(lines.getValue("local"), 100), "local is not timed")
        assertFalse("ping" in hostRowLine(lines.getValue("elm"), 100), "a lost host's old ping is not today's")
        assertNull(pingWords(null))

        assertEquals("227 GB free · 16 CPUs · 66 GB memory", hostFacts(rows[0]))
        assertNull(hostFacts(rows[1]))
        assertEquals("3.4 GB free", hostFacts(HostRow(alias = "x", diskHomeFreeKb = 3_330_000)))
    }

    @Test
    fun the_where_step_shows_each_hosts_facts_and_why_one_cannot_be_picked() {
        val pine = HostChoice(alias = "pine", reachable = true, facts = "227 GB free · 16 CPUs")
        assertEquals("227 GB free · 16 CPUs", whereHostLine(pine, elsewhere = false))
        assertEquals("Signal lost · cannot add there now", whereHostLine(pine.copy(reachable = false), elsewhere = false))
        assertEquals("A folder is added on local only", whereHostLine(pine, elsewhere = true))
        assertNull(whereHostLine(HostChoice(alias = "oak", reachable = true), elsewhere = false))
    }

    // ── Org from the repository owner ──

    private val orgs = OrgDirectory.of(
        json.decodeFromString(
            ListSerializer(OrgDetail.serializer()),
            """[{"id":1,"name":"Acme","rules":[{"id":1,"org_id":1,"owner":"acme"},{"id":4,"org_id":1,"path_prefix":"/srv/acme"}],"hosts":[]},
               {"id":2,"name":"Papaya","rules":[{"id":2,"org_id":2,"owner":"acme","repo":"pos"}],"hosts":["till"]},
               {"id":3,"name":"Lab","rules":[{"id":3,"org_id":3,"host_alias":"lab"}],"hosts":[]}]""",
        ),
    )

    @Test
    fun the_org_comes_from_the_repository_owner_by_the_hubs_own_rule_order() {
        val owner = projectOrg(ProjectSource.Github("acme/app"), "pine", orgs)!!
        assertEquals("Acme" to "from the rule for acme/*", owner.name to owner.why)
        // owner/repo outranks owner.
        assertEquals("Papaya", projectOrg(ProjectSource.Url("git@github.com:ACME/pos.git"), "pine", orgs)!!.name)
        assertEquals("Acme", projectOrg(ProjectSource.New("acme", "new-thing", onGithub = false), "pine", orgs)!!.name)
        // No rule: the host's own org, else a host-only rule, else nothing.
        assertEquals("till belongs to it", projectOrg(ProjectSource.Github("other/x"), "till", orgs)!!.why)
        assertEquals("Lab", projectOrg(ProjectSource.Url("https://github.com/other/x"), "lab", orgs)!!.name)
        assertNull(projectOrg(ProjectSource.Github("other/x"), "pine", orgs))
        assertNull(projectOrg(ProjectSource.Github("acme/app"), "pine", OrgDirectory.EMPTY))
    }

    @Test
    fun a_folders_org_is_said_only_when_its_unknown_owner_cannot_change_it() {
        assertEquals("from the rule for /srv/acme", projectOrg(ProjectSource.Folder("/srv/acme/tool"), "local", orgs)!!.why)
        // Owner rules exist and the folder's owner comes from its remote: not a guess.
        assertNull(projectOrg(ProjectSource.Folder("/home/me/tool"), "local", orgs))
    }

    @Test
    fun a_url_names_its_owner_and_repository() {
        assertEquals("acme" to "app", sourceOwnerRepo(ProjectSource.Url("https://github.com/acme/app.git")))
        assertEquals("acme" to "app", sourceOwnerRepo(ProjectSource.Url("https://github.com:443/acme/app/")))
        assertEquals("acme" to "app", sourceOwnerRepo(ProjectSource.Url("git@github.com:acme/app")))
        assertNull(sourceOwnerRepo(ProjectSource.Url("https://github.com/acme")))
        assertNull(sourceOwnerRepo(ProjectSource.Folder("/srv/x")))
        assertEquals(listOf(OrgRule(id = 1, orgId = 1, owner = "acme"), OrgRule(id = 4, orgId = 1, pathPrefix = "/srv/acme")), orgs.rules.take(2))
    }

    // ── Install agent from Add a host ──

    @Test
    fun a_host_added_without_tmux_offers_install_agent_which_opens_the_review() = runTest {
        val fleet = G58Fleet(emptyList(), setOf("add_host", "discover_hosts", HubCapabilities.INSTALL_AGENT, HubCapabilities.AGENT_INSTALLS))
        val vm = AddHostViewModel(fleet, AddsHosts(tmux = null), backgroundScope, canWrite = true)
        runCurrent()
        vm.open().join()
        runCurrent()
        val pine = vm.state.value.candidates.first { it.alias == "pine.lan" }
        assertNull(vm.state.value.installTarget("pine.lan"), "nothing to install on before it is added")

        vm.add(pine).join()
        runCurrent()
        val s = vm.state.value
        assertEquals("pine-lan", s.installTarget("pine.lan")?.alias)
        assertEquals("Added · tmux is missing", addedHostLine(pine, s.addedRows["pine.lan"]))
        assertEquals("10.0.0.5", addedHostLine(pine, null))
    }

    @Test
    fun install_agent_is_not_offered_where_the_hub_does_not_list_the_job() = runTest {
        val fleet = G58Fleet(emptyList(), setOf("add_host", "discover_hosts"))
        val vm = AddHostViewModel(fleet, AddsHosts(tmux = "3.4"), backgroundScope, canWrite = true)
        runCurrent()
        vm.open().join()
        runCurrent()
        vm.add(vm.state.value.candidates.first()).join()
        runCurrent()
        assertNull(vm.state.value.installTarget("pine.lan"))
        assertEquals("Added · tmux 3.4", addedHostLine(SshHost("pine.lan"), vm.state.value.addedRows["pine.lan"]))
    }

    // ── The repair loader ──

    @Test
    fun the_repair_loader_names_the_hubs_steps_and_ticks_none() {
        for (wait in RepairWait.entries) {
            val steps = repairWaitSteps(wait)
            assertEquals(StepState.Running, steps.first().state)
            assertTrue(steps.drop(1).all { it.state == StepState.Pending })
            assertTrue(steps.none { it.state == StepState.Done }, "the hub answers once: nothing is ticked on the way")
        }
        assertEquals("Repairing the workspace", repairWaitTitle(RepairWait.Repair))
        assertTrue("resuming the conversation" in repairWaitSteps(RepairWait.Recreate).last().label)
    }
}
