package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.DeviceActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DeviceScreenshot
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.devicesOfHolder
import dev.claudefleet.mobile.model.placeOf
import dev.claudefleet.mobile.model.relativeWithin
import dev.claudefleet.mobile.model.sessionHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A screenshot taken from the strip or a chat card, with the device it shows. */
data class DeviceShot(val deviceId: Long, val title: String, val shot: DeviceScreenshot)

data class SessionDevicesUiState(
    /** The hub serves `debug_devices` to this device. */
    val available: Boolean = false,
    /** A person may take a screenshot or release from here. */
    val canAct: Boolean = false,
    /** `host:<alias>#<id>`, once the session's row is known. */
    val holder: String? = null,
    /** The devices the session holds, then those it waits for ([devicesOfHolder]). */
    val devices: List<DebugDevice> = emptyList(),
    /** What is on the wire: `<deviceId>:<move>`. */
    val busy: String? = null,
    /** The screenshot open over the screen. */
    val shot: DeviceShot? = null,
    val error: Friendly? = null,
)

/**
 * The session screen's device strip: the test phones this session holds or
 * waits in line for, read every [POLL_MS] while the screen is shown
 * ([watch], which the route runs in its composition) and the hub serves
 * `debug_devices`. A failed read is quiet — the strip keeps what it had — and
 * only a press (Screenshot, Release) reports a failure.
 *
 * It also answers a chat's `device` cards ([devices]), from the list it last
 * read while that is fresh, so a reply full of cards costs one read.
 */
class SessionDevicesViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: DeviceActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** Unix seconds. */
    private val clock: () -> Long = { epochSeconds() },
) {
    private data class Local(
        val all: List<DebugDevice> = emptyList(),
        val readAt: Long? = null,
        val busy: String? = null,
        val shot: DeviceShot? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    private val holder = fleet.sessions
        .map { rows -> rows.firstOrNull { it.id == sessionId }?.hostAlias?.takeIf { it.isNotBlank() }?.let { sessionHolder(it, sessionId) } }
        .distinctUntilChanged()

    val state: StateFlow<SessionDevicesUiState> = combine(local, fleet.capabilities, holder) { l, caps, h ->
        SessionDevicesUiState(
            available = caps.debugDevices,
            canAct = canWrite && caps.debugDevices,
            holder = h,
            devices = if (h == null || !caps.debugDevices) emptyList() else devicesOfHolder(l.all, h),
            busy = l.busy,
            shot = l.shot,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, SessionDevicesUiState())

    /**
     * Read the list now and every [POLL_MS] after, for as long as the caller
     * runs this and the hub serves `debug_devices`; returns only when cancelled.
     */
    suspend fun watch() {
        fleet.capabilities.map { it.debugDevices }.distinctUntilChanged().collectLatest { served ->
            if (!served) return@collectLatest
            while (true) {
                readQuietly()
                delay(POLL_MS)
            }
        }
    }

    /**
     * Every device this person may see, for a chat's `device` card: the last
     * read while it is younger than [POLL_MS], else a fresh one. Empty where the
     * hub serves no `debug_devices`; a failed read throws, for the card to say.
     */
    suspend fun devices(): List<DebugDevice> {
        if (!state.value.available) return emptyList()
        val l = local.value
        if (l.readAt != null && clock() - l.readAt < POLL_MS / 1000) return l.all
        val list = actions.list(refresh = false).devices
        local.update { it.copy(all = list, readAt = clock()) }
        return list
    }

    /** A screenshot of [deviceId] for a chat card: taken here, kept by the card. */
    suspend fun takeScreenshot(deviceId: Long): DeviceScreenshot = actions.screenshot(deviceId)

    private suspend fun readQuietly() {
        try {
            val list = actions.list(refresh = false).devices
            local.update { it.copy(all = list, readAt = clock()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Quiet: the strip keeps what it last read, and the next read may do better.
        }
    }

    /** Take a screenshot of [d] and open it over the screen. */
    fun screenshot(d: DebugDevice): Job = scope.launch {
        if (!state.value.canAct || local.value.busy != null) return@launch
        local.update { it.copy(busy = "${d.id}:${DeviceMove.Screenshot.name}", error = null) }
        try {
            val shot = actions.screenshot(d.id)
            local.update { it.copy(busy = null, shot = DeviceShot(d.id, d.title, shot)) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** Release the session's claim on [d] (the person confirmed first), or take it out of the line. */
    fun release(d: DebugDevice): Job = scope.launch {
        if (!state.value.canAct || local.value.busy != null) return@launch
        local.update { it.copy(busy = "${d.id}:${DeviceMove.Release.name}", error = null) }
        try {
            val after = actions.release(d.id)
            local.update { l -> l.copy(busy = null, all = l.all.map { if (it.id == after.id) after else it }) }
            readQuietly()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    fun closeShot() {
        local.update { it.copy(shot = null) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    companion object {
        const val POLL_MS: Long = 30_000
    }
}

/**
 * One device of the strip in a line: its state, then "12 min left" for a
 * claim the session holds, or its place for one it waits for ("3rd in line").
 */
fun stripLine(d: DebugDevice, holder: String, nowSeconds: Long): String {
    val held = d.claimedBy == holder
    val tail = if (held) {
        d.claimedUntil?.let { relativeWithin(it, nowSeconds) }?.let { "$it left" }
    } else {
        placeOf(d, holder)?.let { placeName(it) }?.let { if (it == "next") "next in line" else it }
    }
    val state = if (held) "Held" else "Waiting"
    return listOfNotNull(state, d.host.takeIf { it.isNotBlank() }, tail).joinToString(" · ")
}
