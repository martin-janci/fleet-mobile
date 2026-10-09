package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AddHostActions
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.SshHost
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class AddHostFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "mac", sshAlias = "mac.local", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    var refreshed = 0
    override suspend fun refresh() {
        refreshed++
    }
}

private val PINE = SshHost(alias = "pine.lan", hostname = "10.0.0.5", user = "martin", port = 2222)
private val MAC = SshHost(alias = "mac.local", hostname = "192.168.1.4")
private val WSL = SshHost(alias = "ubuntu", hostname = "WSL: Ubuntu")

private class FakeAddHost : AddHostActions {
    val calls = mutableListOf<String>()
    var refuse: HubError? = null

    override suspend fun candidates(): List<SshHost> = listOf(PINE, MAC, WSL).also { calls += "discover" }

    override suspend fun add(alias: String, sshAlias: String): HostRow {
        calls += "add $alias $sshAlias"
        refuse?.let { throw it }
        return HostRow(alias = alias, sshAlias = sshAlias, reachable = true)
    }
}

private val WITH_ADD_HOST = HubCapabilities(tools = setOf("add_host", "discover_hosts"))

/**
 * Adding a host from the phone (redesign 14.12's Radar, claude-fleet
 * contract 13's owner's-phone grant): only where the hub lists `add_host` to
 * this pairing, and a refusal says which command trusts the phone.
 */
class AddHostTest {

    private fun TestScope.vm(
        caps: HubCapabilities = WITH_ADD_HOST,
        actions: FakeAddHost = FakeAddHost(),
        fleet: AddHostFleet = AddHostFleet(caps),
        canWrite: Boolean = true,
    ) = AddHostViewModel(fleet, actions, backgroundScope, canWrite)

    @Test
    fun a_hub_that_does_not_list_add_host_is_never_asked() = runTest {
        val actions = FakeAddHost()
        val readonly = FakeAddHost()
        val a = vm(caps = HubCapabilities(tools = setOf("discover_hosts")), actions = actions)
        val b = vm(actions = readonly, canWrite = false)
        runCurrent()
        a.open()
        b.open()
        runCurrent()
        assertFalse(a.state.value.available)
        assertFalse(b.state.value.available)
        assertEquals(emptyList<String>(), actions.calls + readonly.calls)
    }

    @Test
    fun the_sweep_lists_only_hosts_the_fleet_does_not_have() = runTest {
        val v = vm()
        runCurrent()
        v.open()
        runCurrent()
        assertEquals(listOf("pine.lan", "ubuntu"), v.state.value.candidates.map { it.alias })
        assertEquals(2, v.state.value.blips.size)
        assertEquals("2 in the hub's SSH config", addHostMeta(v.state.value))
    }

    @Test
    fun add_sends_a_fleet_alias_and_reads_the_fleet_again() = runTest {
        val actions = FakeAddHost()
        val fleet = AddHostFleet(WITH_ADD_HOST)
        val v = vm(actions = actions, fleet = fleet)
        runCurrent()
        v.open()
        runCurrent()
        v.add(PINE)
        runCurrent()
        assertEquals(listOf("discover", "add pine-lan pine.lan"), actions.calls)
        assertEquals(setOf("pine.lan"), v.state.value.added)
        assertTrue(v.state.value.blips.first().ready)
        assertEquals(1, fleet.refreshed)
        assertNull(v.state.value.adding)

        // Listed by the fleet now, it stays on screen as Added.
        fleet.hosts.value = fleet.hosts.value + HostRow(alias = "pine-lan", sshAlias = "pine.lan")
        runCurrent()
        assertEquals(listOf("pine.lan", "ubuntu"), v.state.value.candidates.map { it.alias })
        v.add(PINE)
        runCurrent()
        assertEquals(2, actions.calls.size)
    }

    @Test
    fun an_untrusted_phone_is_told_the_command_that_trusts_it() = runTest {
        val actions = FakeAddHost().apply {
            refuse = HubError.Tool(
                "E_FORBIDDEN",
                "adding a host from a device needs the hub's operator to trust it (a full device): fleet-hub client trust Martin's Pixel",
            )
        }
        val v = vm(actions = actions)
        runCurrent()
        v.open()
        runCurrent()
        v.add(PINE)
        runCurrent()
        val error = v.state.value.error!!
        assertEquals("Trust this phone on the hub first", error.title)
        assertEquals("On the hub, run: fleet-hub client trust Martin's Pixel", error.body)
        assertEquals(emptySet(), v.state.value.added)
    }

    @Test
    fun any_other_refusal_reads_as_before() {
        assertNull(trustHint("an org admin administers their own org only"))
        assertEquals("The hub refused that", friendly(HubError.Tool("E_FORBIDDEN", "nope")).title)
    }

    @Test
    fun aliases_and_lines_read_as_the_hub_wants_them() {
        assertEquals("pine-lan", fleetAlias("pine.lan"))
        assertEquals("build-box-1", fleetAlias("_build.box_1"))
        assertEquals("host", fleetAlias("..."))
        assertEquals("martin@10.0.0.5:2222", sshHostLine(PINE))
        assertEquals("192.168.1.4", sshHostLine(MAC))
        assertEquals("WSL: Ubuntu", sshHostLine(WSL))
        assertEquals("None new in the hub's SSH config", addHostMeta(AddHostUiState()))
    }

    @Test
    fun the_hubs_ssh_host_parses() {
        val hosts = json.decodeFromString(
            ListSerializer(SshHost.serializer()),
            """[{"alias":"pine","hostname":"10.0.0.5","user":"martin","port":22},{"alias":"ubuntu","hostname":"WSL: Ubuntu"}]""",
        )
        assertEquals(22, hosts[0].port)
        assertNull(hosts[1].user)
        assertEquals("martin@10.0.0.5", sshHostLine(hosts[0]))
    }
}
