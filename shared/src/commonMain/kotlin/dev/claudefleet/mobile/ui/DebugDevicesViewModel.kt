package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.DeviceActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DeviceHostScan
import dev.claudefleet.mobile.model.DeviceOutput
import dev.claudefleet.mobile.model.relativeWithin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a person may press on one test phone. */
enum class DeviceMove(val label: String) { Claim("Claim"), Release("Release"), Boot("Boot"), Shutdown("Shut down"), Logs("Logs") }

/** One device's log read, kept with the device it came from. */
data class DeviceLogs(val deviceId: Long, val title: String, val output: DeviceOutput)

data class DebugDevicesUiState(
    /** The hub serves `debug_devices` to this token. */
    val available: Boolean = false,
    /** A person may claim, boot and read from here. */
    val canAct: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val devices: List<DebugDevice> = emptyList(),
    val hosts: List<DeviceHostScan> = emptyList(),
    /** What is on the wire: `scan`, or `<deviceId>:<move>`. */
    val busy: String? = null,
    val logs: DeviceLogs? = null,
    val notice: String? = null,
    val error: Friendly? = null,
)

/**
 * Debug devices on the phone (redesign 11.10, on the hub since contract 10):
 * the test phones, emulators and simulators on the fleet's hosts, with Claim,
 * Release, Boot, Shut down and Logs. Install, run and screenshot stay on the
 * desktop, as do a device's label and sharing.
 */
class DebugDevicesViewModel(
    private val fleet: FleetState,
    private val actions: DeviceActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val devices: List<DebugDevice> = emptyList(),
        val hosts: List<DeviceHostScan> = emptyList(),
        val busy: String? = null,
        val logs: DeviceLogs? = null,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<DebugDevicesUiState> = combine(local, fleet.capabilities) { l, caps ->
        DebugDevicesUiState(
            available = caps.debugDevices,
            canAct = canWrite && caps.debugDevices,
            open = l.open,
            loading = l.loading,
            devices = l.devices,
            hosts = l.hosts,
            busy = l.busy,
            logs = l.logs,
            notice = l.notice,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DebugDevicesUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, logs = null, notice = null, error = null) }
        read(refresh = false)
    }

    fun close() {
        local.update { it.copy(open = false, logs = null, notice = null) }
    }

    /** The More row's line wants the list without the sheet open. */
    fun refresh(): Job = scope.launch { read(refresh = false) }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun closeLogs() {
        local.update { it.copy(logs = null) }
    }

    /** Scan every host now, then read the list again. */
    fun scan(): Job = scope.launch {
        if (!state.value.canAct || local.value.busy != null) return@launch
        local.update { it.copy(busy = SCAN, error = null, notice = null) }
        try {
            actions.scan()
            local.update { it.copy(busy = null) }
            read(refresh = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** One press on one device; the row takes the hub's answer, then the list is read again. */
    fun press(device: DebugDevice, move: DeviceMove): Job = scope.launch {
        if (!state.value.canAct || local.value.busy != null || move !in deviceMoves(device)) return@launch
        local.update { it.copy(busy = "${device.id}:${move.name}", error = null, notice = null) }
        try {
            when (move) {
                DeviceMove.Claim -> keep(actions.claim(device.id), "Claimed ${device.title}.")
                DeviceMove.Release -> keep(actions.release(device.id), "Released ${device.title}.")
                DeviceMove.Shutdown -> keep(actions.shutdown(device.id), "Shut down ${device.title}.")
                DeviceMove.Boot -> {
                    val said = actions.boot(device.id)
                    local.update { it.copy(notice = "Booting ${device.title}" + (said.takeIf { s -> s.isNotBlank() }?.let { s -> ": $s" } ?: ".")) }
                }
                DeviceMove.Logs -> {
                    val out = actions.logs(device.id, LOG_LINES)
                    local.update { it.copy(logs = DeviceLogs(device.id, device.title, out)) }
                }
            }
            local.update { it.copy(busy = null) }
            if (move != DeviceMove.Logs) read(refresh = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    private fun keep(d: DebugDevice, notice: String) {
        local.update { l -> l.copy(devices = l.devices.map { if (it.id == d.id) d else it }, notice = notice) }
    }

    private suspend fun read(refresh: Boolean) {
        if (!state.value.available) return
        local.update { it.copy(loading = true) }
        try {
            val list = actions.list(refresh)
            local.update { it.copy(loading = false, devices = list.devices, hosts = list.hosts) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    private companion object {
        const val SCAN = "scan"
        const val LOG_LINES = 200
    }
}

/** The More row: "3 test phones · 1 in use", "No test phones found". */
fun devicesLine(devices: List<DebugDevice>): String {
    if (devices.isEmpty()) return "No test phones found · Scan from here"
    val inUse = devices.count { it.claimedBy != null }
    val count = "${devices.size} test phone${if (devices.size == 1) "" else "s"}"
    return if (inUse == 0) count else "$count · $inUse in use"
}

/** The device's state in a word or two: Ready, In use, Shut down, Offline, Not trusted, Gone. */
fun deviceState(d: DebugDevice): String = when {
    d.claimedBy != null -> "In use"
    d.ready -> "Ready"
    d.state == "shutdown" -> "Shut down"
    d.state == "unauthorized" -> "Not trusted"
    d.state == "missing" -> "Gone"
    d.state == "offline" -> "Offline"
    else -> d.state.replaceFirstChar { it.uppercase() }
}

/** "physical · Android 16 · Pixel 9 · mac", then who holds it and for how long. */
fun deviceLine(d: DebugDevice, nowSeconds: Long): String {
    val os = listOfNotNull(
        when (d.platform) {
            "android" -> "Android"
            "ios" -> "iOS"
            else -> null
        },
        d.osVersion?.takeIf { it.isNotBlank() },
    ).joinToString(" ").ifBlank { null }
    val held = d.claimedBy?.let { holder ->
        listOfNotNull(
            "held by ${holderName(holder)}",
            d.claimNote?.takeIf { it.isNotBlank() },
            d.claimedUntil?.let { relativeWithin(it, nowSeconds) }?.let { "$it left" },
        ).joinToString(" · ")
    }
    return listOfNotNull(d.kind.takeIf { it.isNotBlank() }, os, d.model?.takeIf { it.isNotBlank() }, d.host.takeIf { it.isNotBlank() }, held)
        .joinToString(" · ")
}

/**
 * A claim's holder in words: `host:mercury#42` is "a session on mercury",
 * `client:Martin's Pixel` is that device's name.
 */
fun holderName(holder: String): String = when {
    holder.startsWith("host:") -> "a session on " + holder.removePrefix("host:").substringBefore('#')
    holder.startsWith("client:") -> holder.removePrefix("client:")
    else -> holder
}

/**
 * What may be pressed on [d]: Claim a ready one nobody holds, Release a held
 * one (a person may release any claim), Boot a stopped emulator or
 * simulator, Shut one down, read the Logs of a ready one. A physical phone is
 * never booted or shut down from here.
 */
fun deviceMoves(d: DebugDevice): List<DeviceMove> = buildList {
    val virtual = d.kind == "emulator" || d.kind == "simulator"
    if (d.claimedBy == null && d.ready) add(DeviceMove.Claim)
    if (d.claimedBy != null) add(DeviceMove.Release)
    if (virtual && !d.ready && d.state != "unauthorized") add(DeviceMove.Boot)
    if (virtual && d.ready) add(DeviceMove.Shutdown)
    if (d.ready) add(DeviceMove.Logs)
}
