package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.DeviceActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DebugDeviceList
import dev.claudefleet.mobile.model.DeviceHostScan
import dev.claudefleet.mobile.model.DeviceOutput
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DevicesFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "mac", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private val PIXEL = DebugDevice(id = 1, title = "Pixel 9", host = "mac", platform = "android", kind = "physical", osVersion = "16", state = "online", ready = true)
private val AVD = DebugDevice(
    id = 2, title = "Pixel_8_API_35", host = "mercury", platform = "android", kind = "emulator", state = "online", ready = true,
    claimedBy = "host:mercury#42", claimNote = "Mobile push",
)
private val SIM = DebugDevice(id = 3, title = "iPhone 17", host = "mac", platform = "ios", kind = "simulator", osVersion = "26", state = "shutdown")

private class FakeDeviceActions : DeviceActions {
    val calls = mutableListOf<String>()
    var devices = listOf(PIXEL, AVD, SIM)
    var refuse = false

    override suspend fun list(refresh: Boolean): DebugDeviceList {
        calls += "list $refresh"
        return DebugDeviceList(devices, listOf(DeviceHostScan("mac", 100), DeviceHostScan("oci-arm", error = "adb not found")))
    }

    override suspend fun scan() {
        calls += "scan"
    }

    private fun answer(id: Long, change: (DebugDevice) -> DebugDevice): DebugDevice {
        if (refuse) throw HubError.Tool("E_CONFLICT", "Pixel 9 is claimed by host:mac#7")
        val d = change(devices.first { it.id == id })
        devices = devices.map { if (it.id == id) d else it }
        return d
    }

    override suspend fun claim(deviceId: Long): DebugDevice {
        calls += "claim $deviceId"
        return answer(deviceId) { it.copy(claimedBy = "client:Martin's Pixel") }
    }

    override suspend fun release(deviceId: Long): DebugDevice {
        calls += "release $deviceId"
        return answer(deviceId) { it.copy(claimedBy = null, claimNote = null) }
    }

    override suspend fun boot(deviceId: Long): String = "booting".also { calls += "boot $deviceId" }

    override suspend fun shutdown(deviceId: Long): DebugDevice {
        calls += "shutdown $deviceId"
        return answer(deviceId) { it.copy(state = "shutdown", ready = false) }
    }

    override suspend fun logs(deviceId: Long, lines: Int): DeviceOutput {
        calls += "logs $deviceId $lines"
        return DeviceOutput(output = "I/ActivityManager: Start proc", truncated = true)
    }
}

private val WITH_DEVICES = HubCapabilities(tools = setOf("debug_devices"))

/**
 * Debug devices on the phone (redesign 11.10): the test phones on the
 * fleet's hosts, read and driven only where the hub serves `debug_devices`.
 */
class DebugDevicesTest {

    private fun TestScope.devices(
        caps: HubCapabilities = WITH_DEVICES,
        actions: FakeDeviceActions = FakeDeviceActions(),
        canWrite: Boolean = true,
    ) = DebugDevicesViewModel(DevicesFleet(caps), actions, backgroundScope, canWrite)

    @Test
    fun a_hub_without_debug_devices_is_never_asked() = runTest {
        val actions = FakeDeviceActions()
        val vm = devices(caps = HubCapabilities(), actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        assertFalse(vm.state.value.available)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun opening_lists_the_devices_and_the_hosts_that_failed() = runTest {
        val vm = devices()
        runCurrent()
        vm.open()
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.devices.map { it.id })
        assertEquals("adb not found", vm.state.value.hosts.single { it.host == "oci-arm" }.error)
    }

    @Test
    fun claim_takes_the_hubs_answer_and_reads_the_list_again() = runTest {
        val actions = FakeDeviceActions()
        val vm = devices(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.press(PIXEL, DeviceMove.Claim)
        runCurrent()
        assertEquals(listOf("list false", "claim 1", "list false"), actions.calls)
        assertEquals("client:Martin's Pixel", vm.state.value.devices.first().claimedBy)
        assertEquals("Claimed Pixel 9.", vm.state.value.notice)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun a_refused_claim_says_why_and_changes_nothing() = runTest {
        val actions = FakeDeviceActions().apply { refuse = true }
        val vm = devices(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.press(PIXEL, DeviceMove.Claim)
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.devices.first().claimedBy)
    }

    @Test
    fun a_move_the_device_does_not_allow_is_never_sent() = runTest {
        val actions = FakeDeviceActions()
        val vm = devices(actions = actions)
        runCurrent()
        vm.open()
        runCurrent()
        vm.press(PIXEL, DeviceMove.Shutdown)
        vm.press(SIM, DeviceMove.Logs)
        runCurrent()
        assertEquals(listOf("list false"), actions.calls)
    }

    @Test
    fun logs_open_with_the_device_they_came_from() = runTest {
        val vm = devices()
        runCurrent()
        vm.open()
        runCurrent()
        vm.press(PIXEL, DeviceMove.Logs)
        runCurrent()
        val logs = vm.state.value.logs
        assertEquals("Pixel 9", logs?.title)
        assertTrue(logs!!.output.truncated)
        vm.closeLogs()
        runCurrent()
        assertNull(vm.state.value.logs)
    }

    @Test
    fun scan_then_list_and_a_readonly_phone_does_neither() = runTest {
        val actions = FakeDeviceActions()
        val vm = devices(actions = actions)
        runCurrent()
        vm.scan()
        runCurrent()
        assertEquals(listOf("scan", "list false"), actions.calls)

        val ro = FakeDeviceActions()
        val readonly = devices(actions = ro, canWrite = false)
        runCurrent()
        readonly.scan()
        readonly.press(PIXEL, DeviceMove.Claim)
        runCurrent()
        assertFalse(readonly.state.value.canAct)
        assertEquals(emptyList<String>(), ro.calls)
    }

    @Test
    fun each_device_offers_only_what_it_can_do() {
        assertEquals(listOf(DeviceMove.Claim, DeviceMove.Logs), deviceMoves(PIXEL))
        assertEquals(listOf(DeviceMove.Release, DeviceMove.Shutdown, DeviceMove.Logs), deviceMoves(AVD))
        assertEquals(listOf(DeviceMove.Boot), deviceMoves(SIM))
        assertEquals(emptyList<DeviceMove>(), deviceMoves(PIXEL.copy(state = "unauthorized", ready = false)))
    }

    @Test
    fun a_row_says_its_state_where_it_is_and_who_holds_it() {
        assertEquals("Ready", deviceState(PIXEL))
        assertEquals("In use", deviceState(AVD))
        assertEquals("Shut down", deviceState(SIM))
        assertEquals("Not trusted", deviceState(PIXEL.copy(state = "unauthorized", ready = false)))
        assertEquals("physical · Android 16 · mac", deviceLine(PIXEL, 0))
        assertEquals(
            "emulator · Android · mercury · held by a session on mercury · Mobile push · 30 min left",
            deviceLine(AVD.copy(claimedUntil = 1_800), 0),
        )
        assertEquals("Martin's Pixel", holderName("client:Martin's Pixel"))
    }

    @Test
    fun the_more_row_counts_the_phones_and_those_in_use() {
        assertEquals("3 test phones · 1 in use", devicesLine(listOf(PIXEL, AVD, SIM)))
        assertEquals("1 test phone", devicesLine(listOf(PIXEL)))
        assertEquals("No test phones found · Scan from here", devicesLine(emptyList()))
    }

    @Test
    fun the_hubs_device_row_parses() {
        val list = json.decodeFromString(
            DebugDeviceList.serializer(),
            """{"devices":[{"id":5,"title":"Pixel 9","host":"mac","platform":"android","kind":"physical","key":"39X","serial":"39X","name":"Pixel 9","model":"Pixel 9","os_version":"16","state":"online","ready":true,"shared":false,"claimed_by":"client:phone","claimed_until":1700000000,"first_seen_at":1,"last_seen_at":2}],"hosts":[{"host":"mac","scanned_at":3},{"host":"pine","error":"ssh: timeout"}]}""",
        )
        val d = list.devices.single()
        assertEquals("16", d.osVersion)
        assertEquals("client:phone", d.claimedBy)
        assertEquals(1_700_000_000L, d.claimedUntil)
        assertEquals("ssh: timeout", list.hosts[1].error)
    }
}
